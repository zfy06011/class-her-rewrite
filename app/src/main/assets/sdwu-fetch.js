// 固定学校域名、接口与本人表单；不读取或传递 Cookie，不写日志。
(function () {
  const key = __REQUEST_KEY__;
  const root = window;
  const controller = new AbortController();
  let cancelled = false;
  root[key] = {state: 'pending'};
  root[key + '_cancel'] = () => { cancelled = true; controller.abort(); };
  const timeout = setTimeout(() => controller.abort(), 25000);
  const finish = result => { if (root[key]) root[key] = result; clearTimeout(timeout); };
  const valid = win => {
    try {
      return win.location.protocol === 'https:' && win.location.hostname === 'jwxt.sdwu.edu.cn'
        && win.location.port === '';
    } catch { return false; }
  };
  if (!valid(root)) { finish({state: 'error', code: 'LOGIN_REQUIRED'}); return; }
  const visited = new Set();
  function find(win, depth = 0) {
    if (depth > 8 || visited.has(win) || !valid(win)) return [];
    visited.add(win);
    const candidates = [];
    try {
      // 只接受本人课表页，不从登录表单或其他学生查询页面构造身份。
      if (/\/student\/wsxk\.xskcb[^/]*\.jsp$/.test(win.location.pathname)) {
        // 已验证的报表可能只有隐藏字段，没有 form 标签。
        const value = names => {
          const fields = names.flatMap(name => Array.from(win.document.querySelectorAll(`input[name="${name}"], select[name="${name}"]`)));
          const values = [...new Set(fields.map(field => field.value?.trim()).filter(Boolean))];
          return values.length === 1 ? values[0] : undefined;
        };
        const account = value(['xh']);
        const year = value(['xn']);
        const semester = value(['xq_m', 'xq']);
        if (account && /^\d{4}$/.test(year ?? '') && /^[01]$/.test(semester ?? '')) {
          candidates.push({win, account, year, semester});
        }
      }
      for (let i = 0; i < win.frames.length; i++) candidates.push(...find(win.frames[i], depth + 1));
    } catch { /* 跨源框架不可读；用户需在学校 HTTPS 个人课表页面重试。 */ }
    return candidates;
  }
  const forms = find(root);
  if (forms.length !== 1) { finish({state: 'error', code: 'LOGIN_REQUIRED'}); return; }
  const source = forms[0];
  // 身份只来自当前本人课表表单，没有允许用户输入任意学号的入口。
  const params = btoa(`xn=${encodeURIComponent(source.year)}&xq=${encodeURIComponent(source.semester)}&xh=${encodeURIComponent(source.account)}`);
  const url = new URL('/sdnzjw/student/wsxk.xskcb10319.jsp', 'https://jwxt.sdwu.edu.cn');
  url.searchParams.set('params', params);
  (async () => {
    try {
      const response = await source.win.fetch(url.href, {
        credentials: 'same-origin', cache: 'no-store', signal: controller.signal
      });
      if (!response.ok) {
        finish({state: 'error', code: response.status === 401 || response.status === 403 ? 'LOGIN_REQUIRED' : 'NETWORK'});
        return;
      }
      if (response.url && new URL(response.url).origin !== url.origin) {
        finish({state: 'error', code: 'LOGIN_REQUIRED'}); return;
      }
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength > 2000000) { finish({state: 'error', code: 'INCOMPLETE'}); return; }
      const charset = /charset\s*=\s*["']?([^;\s"']+)/i.exec(response.headers.get('content-type') ?? '')?.[1] ?? 'gbk';
      let html;
      try { html = new TextDecoder(charset, {fatal: true}).decode(bytes); }
      catch { finish({state: 'error', code: 'INVALID_DATA'}); return; }
      finish({state: 'ready', html, account: source.account, year: source.year, semester: source.semester});
    } catch (error) {
      finish({state: 'error', code: error?.name === 'AbortError' ? (cancelled ? 'CANCELLED' : 'TIMEOUT') : 'NETWORK'});
    }
  })();
})();
