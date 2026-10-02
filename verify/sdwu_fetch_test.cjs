// 只使用合成账号和合成响应，验证桥接边界，不触网。
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {TextDecoder} = require('node:util');
const source = readFileSync('app/src/main/assets/sdwu-fetch.js', 'utf8');

async function run({host = 'jwxt.sdwu.edu.cn', protocol = 'https:', duplicate = false,
  remote = false, html = '<html>合成课表</html>', status = 200, hasFields = true,
  withForm = true, ambiguousField = false, charset = 'utf-8', rawBytes,
  deferUntilAbort = false, forceTimeout = false, directPage = false, afterStart} = {}) {
  const calls = [];
  const document = {
    forms: withForm ? [{}] : [],
    querySelectorAll: selector => {
      if (!hasFields) return [];
      const name = /name="([^"]+)"/.exec(selector)[1];
      const values = {xh: 'synthetic-account', xn: '2026', xq_m: '0'};
      if (!(name in values)) return [];
      return [{value: values[name]}, ...(ambiguousField && name === 'xh' ? [{value: 'other-synthetic-account'}] : [])];
    },
  };
  const child = {
    location: {protocol, hostname: host, port: '', pathname: '/sdnzjw/student/wsxk.xskcb.jsp'},
    document, frames: [],
    fetch: async (url, options) => {
      calls.push({url, options});
      if (deferUntilAbort) await new Promise((_, reject) => {
        const abort = () => reject(Object.assign(new Error('synthetic abort'), {name: 'AbortError'}));
        if (options.signal.aborted) abort(); else options.signal.addEventListener('abort', abort, {once: true});
      });
      return {ok: status >= 200 && status < 300, status,
        url: remote ? 'https://example.invalid/' : url,
        headers: {get: () => `text/html;charset=${charset}`},
        arrayBuffer: async () => rawBytes ?? new TextEncoder().encode(html).buffer};
    },
  };
  const root = directPage ? {...child} : {...child, location: {...child.location, pathname: '/sdnzjw/main.jsp'},
    document: {forms: [], querySelectorAll: () => []}, frames: [child, ...(duplicate ? [{...child}] : [])]};
  const sandbox = {window: root, URL, TextDecoder, AbortController,
    setTimeout: (fn, delay) => setTimeout(fn, forceTimeout && delay === 25000 ? 0 : delay), clearTimeout,
    btoa: value => Buffer.from(value, 'binary').toString('base64')};
  vm.runInNewContext(source.replace('__REQUEST_KEY__', JSON.stringify('syntheticSlot')), sandbox);
  if (afterStart) afterStart(root);
  for (let i = 0; i < 20 && root.syntheticSlot?.state === 'pending'; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
  }
  if (root.syntheticSlot?.state === 'pending') {
    root.syntheticSlot_cancel();
    throw new Error('Synthetic fetch did not settle');
  }
  // 再让取消后的异步 catch 完成，以检验删除后的结果槽不会复活。
  await new Promise(resolve => setTimeout(resolve, 0));
  return {payload: root.syntheticSlot, calls};
}

(async () => {
  const success = await run();
  assert.equal(success.payload.state, 'ready');
  assert.equal(success.payload.account, 'synthetic-account');
  const call = success.calls[0];
  assert.equal(new URL(call.url).pathname, '/sdnzjw/student/wsxk.xskcb10319.jsp');
  const params = Buffer.from(new URL(call.url).searchParams.get('params'), 'base64').toString();
  assert.equal(params, 'xn=2026&xq=0&xh=synthetic-account');
  assert.equal(call.options.credentials, 'same-origin');
  assert.equal(call.options.cache, 'no-store');
  assert.equal(call.options.headers, undefined);
  assert.equal((await run({withForm: false})).payload.state, 'ready');
  assert.equal((await run({directPage: true, withForm: false})).payload.state, 'ready');
  for (const options of [{host: 'example.invalid'}, {protocol: 'http:'}, {duplicate: true}, {hasFields: false}, {ambiguousField: true}]) {
    const result = await run(options);
    assert.equal(result.payload.code, 'LOGIN_REQUIRED');
    assert.equal(result.calls.length, 0);
  }
  assert.equal((await run({remote: true})).payload.code, 'LOGIN_REQUIRED');
  assert.equal((await run({status: 500})).payload.code, 'NETWORK');
  for (const status of [401, 403]) assert.equal((await run({status})).payload.code, 'LOGIN_REQUIRED');
  assert.equal((await run({html: 'x'.repeat(2000001)})).payload.code, 'INCOMPLETE');
  const gbk = Uint8Array.from(Buffer.from('bacfb3c9bfceb1ed', 'hex')).buffer;
  assert.equal((await run({charset: 'gbk', rawBytes: gbk})).payload.html, '合成课表');
  assert.equal((await run({charset: 'bad-charset'})).payload.code, 'INVALID_DATA');
  assert.equal((await run({rawBytes: Uint8Array.of(0xff).buffer})).payload.code, 'INVALID_DATA');
  assert.equal((await run({deferUntilAbort: true, forceTimeout: true})).payload.code, 'TIMEOUT');
  assert.equal((await run({deferUntilAbort: true, afterStart: root => root.syntheticSlot_cancel()})).payload.code, 'CANCELLED');
  const cleared = await run({deferUntilAbort: true, afterStart: root => {
    root.syntheticSlot_cancel(); delete root.syntheticSlot; delete root.syntheticSlot_cancel;
  }});
  assert.equal(cleared.payload, undefined);
  assert.equal(cleared.calls[0].options.signal.aborted, true);
  console.log('PASS: school origin, form-free fields, ambiguity, request scope, redirects, status, GBK, decoding, timeout, cancel and cleanup');
})().catch(error => { console.error(error); process.exitCode = 1; });
