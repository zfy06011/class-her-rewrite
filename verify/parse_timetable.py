#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最小验证用解析器：山东女子学院（青果 KINGOSOFT）教务“本人课表”导出文件。

范围与边界
----------
* 只做解析预览与两次导出一致性比对：不登录、不建库、不写业务数据。
* 本程序**不是**最终实现，正式解析器需按 AD-5 用 Kotlin 重写并接入统一模型。
* 本文件按 ``docs/HANDOFF.md`` 8.5 记录的结构重建（原程序已在工作区丢失）。
  在拿到用户真实导出文件复核前，字段切分假设属于**未验证**，解析疑点会显式输出，
  不会静默丢弃或臆造数据。

失败模式（必须显式报错，绝不能退化成“零课程”）
------------------------------------------------
``SESSION_LOST`` / ``EMPTY_GRID`` / ``NO_TABLE`` / ``NO_GRID`` / ``TRUNCATED`` /
``ENCODING`` / ``NOT_HTML``

用法
----
    python verify/parse_timetable.py <导出文件> [--json 输出.json]
                                     [--semester-weeks 19] [--periods 12] [--no-mask]
    python verify/parse_timetable.py --compare <导出A> <导出B>
    python verify/parse_timetable.py --self-test
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field, asdict
from typing import Iterable

try:  # 缺失依赖时显式说明，不将运行环境问题伪装成页面错误。
    from lxml import html as lxml_html
except Exception:  # pragma: no cover
    lxml_html = None


# --------------------------------------------------------------------------
# 错误类型
# --------------------------------------------------------------------------
class ParseError(Exception):
    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code
        self.detail = detail


SESSION_LOST_MARKERS = ("凭证已失效", "请重新登录", "session", "登录超时")

WEEKDAY_NAMES = {1: "周一", 2: "周二", 3: "周三", 4: "周四", 5: "周五", 6: "周六", 7: "周日"}

# “周次表达式[节次]”，例如 1-2,5-13[1-2] / 10[1-2] / 2,6-8 双 [11-12]
WEEK_EXPR_RE = re.compile(r"(?P<weeks>[0-9,\-\s]*?)\s*(?P<parity>[单双])?\s*\[(?P<periods>[0-9,\-\s]+)\]")


# --------------------------------------------------------------------------
# 数据模型
# --------------------------------------------------------------------------
@dataclass(frozen=True)
class Meeting:
    """一条归一化后的上课安排。"""

    course: str
    teacher: str
    room: str
    weekday: int
    periods: tuple[int, ...]
    weeks: tuple[int, ...]
    parity: str = ""  # "" | "单" | "双"

    def key(self) -> tuple:
        """去重键：不含大节行号，跨大节重复出现的同一门课因此折叠为一条。"""
        return (self.course, self.teacher, self.room, self.weekday, self.periods, self.weeks)

    def display(self) -> str:
        wk = ",".join(str(w) for w in self.weeks)
        pd = ",".join(str(p) for p in self.periods)
        parity = f" {self.parity}" if self.parity else ""
        return f"{WEEKDAY_NAMES[self.weekday]} 第{wk}周{parity} 第{pd}节 | {self.course} | {self.teacher} | {self.room or '(教室未填)'}"


@dataclass
class ParseResult:
    source: str
    sha256: str
    identity: dict[str, str] = field(default_factory=dict)
    meetings: list[Meeting] = field(default_factory=list)
    unscheduled: list[dict[str, str]] = field(default_factory=list)
    doubts: list[str] = field(default_factory=list)
    stats: dict[str, object] = field(default_factory=dict)
    source_scope: tuple[str, str, str] | None = None  # 仅内存核对，不输出学号或其散列。

    def to_json(self) -> dict:
        return {
            "source": self.source,
            "sha256": self.sha256,
            "identity": self.identity,
            "stats": self.stats,
            "meetings": [asdict(m) | {"display": m.display()} for m in self.meetings],
            "unscheduled": self.unscheduled,
            "doubts": self.doubts,
        }


# --------------------------------------------------------------------------
# 读取与预检
# --------------------------------------------------------------------------
def read_export(path: str) -> tuple[str, str]:
    raw = open(path, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    if not raw.strip():
        raise ParseError("TRUNCATED", "文件为空")
    text = None
    for enc in ("gbk", "utf-8", "gb18030"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        raise ParseError("ENCODING", "既非 GBK 也非 UTF-8，无法解码")
    return text, sha


def precheck(text: str) -> None:
    """顺序很关键：先判会话失效，再判“根本不是网页”，最后判截断。

    任何一步失败都必须显式报错——尤其是会话失效，绝不能退化成“零课程”。
    """
    body = text.strip()
    if "凭证已失效" in body or "请重新登录" in body:
        raise ParseError("SESSION_LOST", "返回“凭证已失效，请重新登录”，需用户重新登录")
    low = body.lower()
    if "<html" not in low and "<table" not in low:
        raise ParseError("NOT_HTML", "既不含 <html> 也不含 <table>，不是课表导出")
    if low.count("<table") != low.count("</table>"):
        raise ParseError("TRUNCATED", f"<table> 与 </table> 数量不匹配"
                                      f"（{low.count('<table')} vs {low.count('</table>')}）")
    stripped = low.rstrip()
    if stripped.rfind("<") > stripped.rfind(">"):
        raise ParseError("TRUNCATED", "文件在标签中间结束")
    if "</body>" not in low and "</html>" not in low:
        raise ParseError("TRUNCATED", "缺少 </body> 与 </html> 结束标记")


def _text_of(el) -> str:
    return re.sub(r"\s+", " ", el.text_content()).strip()


BLOCK_TAGS = ("div", "p", "li", "table", "tr")


def _lines_of_html(fragment: str) -> list[str]:
    """把一段 HTML 片段转成按 <br>/块级结束标签分行的纯文本行。"""
    txt = re.sub(r"(?i)<br\s*/?>", "\n", fragment)
    txt = re.sub(r"(?i)</(?:p|div|li|tr|td|table|span)>", "\n", txt)
    txt = re.sub(r"<!--.*?-->", "", txt, flags=re.S)
    txt = re.sub(r"<[^>]+>", " ", txt)
    txt = txt.replace("&nbsp;", " ").replace("&amp;", "&")
    return [re.sub(r"\s+", " ", s).strip() for s in txt.split("\n") if re.sub(r"\s+", "", s)]


def _cell_blocks(cell) -> list[list[str]]:
    """把一个网格单元格切成若干“课程块”，每块是若干行文本（块内顺序：课程名/教师/周次[节次]/教室）。"""
    if lxml_html is not None:
        children = [c for c in cell.iterchildren() if isinstance(c.tag, str)]
        child_blocks: list[list[str]] = []
        if children:
            for child in children:
                lines = _lines_of_html(lxml_html.tostring(child, encoding="unicode"))
                if lines:
                    child_blocks.append(lines)
            if len(child_blocks) > 1 or (child_blocks and len(child_blocks[0]) > 1):
                return child_blocks
        return [_lines_of_html(lxml_html.tostring(cell, encoding="unicode"))]
    return [_lines_of_html(cell if isinstance(cell, str) else _text_of(cell))]


def looks_like_room(s: str) -> bool:
    if not s or len(s) > 30:
        return False
    if WEEK_EXPR_RE.search(s):
        return False
    return bool(re.search(r"\d", s)) or bool(re.search(r"楼|室|馆|场|教室|实验|机|校区|区|馆", s))


# --------------------------------------------------------------------------
# 周次 / 节次
# --------------------------------------------------------------------------
def expand_numbers(spec: str) -> tuple[int, ...]:
    out: set[int] = set()
    for part in spec.replace("，", ",").split(","):
        part = part.strip()
        if not part:
            continue
        m = re.fullmatch(r"(\d+)\s*[-–~]\s*(\d+)", part)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            if b < a:
                raise ParseError("BAD_WEEK", f"区间倒序：{part}")
            out.update(range(a, b + 1))
        elif part.isdigit():
            out.add(int(part))
        else:
            raise ParseError("BAD_WEEK", f"无法识别的周次/节次片段：{part}")
    return tuple(sorted(out))


def parse_week_expr(expr: str) -> tuple[tuple[int, ...], tuple[int, ...], str]:
    m = WEEK_EXPR_RE.search(expr)
    if not m:
        raise ParseError("BAD_WEEK", f"无法解析周次表达式：{expr!r}")
    weeks = expand_numbers(m.group("weeks"))
    periods = expand_numbers(m.group("periods"))
    if not weeks or not periods:
        raise ParseError("BAD_WEEK", f"周次或节次为空：{expr!r}")
    if min(weeks) < 1 or min(periods) < 1:
        raise ParseError("BAD_WEEK", "周次和节次必须从 1 开始")
    parity = m.group("parity") or ""
    if parity:
        expected = 1 if parity == "单" else 0
        weeks = tuple(w for w in weeks if w % 2 == expected)
        if not weeks:
            raise ParseError("BAD_WEEK", "周次与单双周条件没有交集")
    return weeks, periods, parity


def big_periods_for(periods: Iterable[int]) -> set[int]:
    """大节行号：第 n 大节含第 2n-1、2n 小节。"""
    return {(p + 1) // 2 for p in periods}


# --------------------------------------------------------------------------
# 解析主体
# --------------------------------------------------------------------------
GRID_HEADER_HINTS = ("星期一", "周一", "星期二", "周二")
IDENTITY_HINTS = ("学号", "姓名", "课程门数", "本学期修读总学分")
UNSCHEDULED_HINTS = ("上课班级代码", "修读性质", "选课状态", "总学时")


def grid_from_weekday_header(tables, periods_per_day: int) -> dict:
    """真实导出没有 k-ID：按星期表头定位，展开 rowspan/colspan 后映射。"""
    weekday_labels = {"星期" + c: i for i, c in enumerate("一二三四五六日", 1)}
    for table in tables:
        rows = table.xpath("./tr | ./tbody/tr")
        if not rows:
            continue
        expanded = {}
        for row_index, row in enumerate(rows):
            col_index = 0
            for cell in row.xpath("./td | ./th"):
                while (row_index, col_index) in expanded:
                    col_index += 1
                try:
                    colspan = int(cell.get("colspan", "1"))
                    rowspan = int(cell.get("rowspan", "1"))
                except ValueError:
                    raise ParseError("INCOMPLETE_GRID", "表格跨度不是整数")
                if not (1 <= colspan <= 10 and 1 <= rowspan <= 10):
                    raise ParseError("INCOMPLETE_GRID", "表格跨度超出已验证格式")
                for dr in range(rowspan):
                    for dc in range(colspan):
                        pos = (row_index + dr, col_index + dc)
                        if pos in expanded:
                            raise ParseError("INCOMPLETE_GRID", "表格跨度重叠")
                        expanded[pos] = cell
                col_index += colspan
        columns = {}
        for (row, col), cell in expanded.items():
            if row == 0:
                label = re.sub(r"\s+", "", cell.text_content())
                if label in weekday_labels:
                    columns[weekday_labels[label]] = col
        if not columns:
            continue
        big_period_count = (periods_per_day + 1) // 2
        if set(columns) != set(range(1, 8)) or len(rows) != big_period_count + 1:
            raise ParseError("INCOMPLETE_GRID", "星期列或大节行不完整，不能视为完整课表")
        result = {}
        for day, col in columns.items():
            for big in range(1, big_period_count + 1):
                if (big, col) not in expanded:
                    raise ParseError("INCOMPLETE_GRID", "星期列缺少数据单元格")
                result[(day, big)] = expanded[(big, col)]
        return result
    return {}


def parse_export(path: str, *, semester_weeks: int = 19, periods_per_day: int = 12, mask: bool = True) -> ParseResult:
    text, sha = read_export(path)
    return parse_text(text, source=path, sha256=sha, semester_weeks=semester_weeks,
                      periods_per_day=periods_per_day, mask=mask)


def parse_text(text: str, *, source: str = "<memory>", sha256: str = "", semester_weeks: int = 19,
               periods_per_day: int = 12, mask: bool = True) -> ParseResult:
    """直接解析学校 HTML 响应；调用方不必创建导出文件。"""
    if lxml_html is None:
        raise ParseError("DEPENDENCY", "缺少 lxml；请使用 uv run --no-project --with lxml python 执行")
    precheck(text)
    res = ParseResult(source=source, sha256=sha256 or hashlib.sha256(text.encode("utf-8")).hexdigest())

    if lxml_html is not None:
        doc = lxml_html.fromstring(text)
        tables = doc.xpath("//table")
    else:  # pragma: no cover - 仅在没有 lxml 时使用
        tables = []
        doc = None

    if not tables:
        # 没有 lxml 或页面没有 table：不要谎报空课表
        raise ParseError("NO_TABLE", "未找到任何 <table>，无法确认这是课表导出")

    grid_cells: dict[tuple[int, int], object] = {}
    for t in tables:
        for cell in t.xpath(".//td[@id] | .//th[@id]"):
            m = re.fullmatch(r"k(\d)(\d)", (cell.get("id") or "").strip())
            if m:
                grid_cells[(int(m.group(1)), int(m.group(2)))] = cell

    if not grid_cells:
        grid_cells = grid_from_weekday_header(tables, periods_per_day)

    has_identity = any(h in text for h in IDENTITY_HINTS)
    has_unscheduled = any(h in text for h in UNSCHEDULED_HINTS)
    if not grid_cells:
        raise ParseError(
            "NO_GRID",
            "没有受支持的星期／大节网格；可能是登录页或其他报表，不能当作课表",
        )
    expected_cells = {(day, big) for day in range(1, 8) for big in range(1, (periods_per_day + 1) // 2 + 1)}
    if set(grid_cells) != expected_cells:
        raise ParseError("INCOMPLETE_GRID", "星期／大节单元格不完整，不得据此判断学校删课")

    seen: set[tuple] = set()
    meetings: list[Meeting] = []
    pending: list[str] = []
    nonempty_cells = 0

    for (weekday, big), cell in sorted(grid_cells.items()):
        if not (1 <= weekday <= 7):
            continue
        blocks = [b for b in _cell_blocks(cell) if b]
        if not any(WEEK_EXPR_RE.search(ln) for b in blocks for ln in b):
            if any(ln.strip() not in ("", "\u00a0") for b in blocks for ln in b):
                pending.append(f"k{weekday}{big}: 有文本但未识别周次表达式，须人工核对")
            continue
        nonempty_cells += 1
        for lines in blocks:
            anchor_idx = [i for i, ln in enumerate(lines) if WEEK_EXPR_RE.search(ln)]
            if not anchor_idx:
                pending.append(f"k{weekday}{big}: 无周次表达式的文本块 {lines!r}")
                continue
            consumed: set[int] = set()
            for j in anchor_idx:
                anchor = lines[j]
                consumed.add(j)
                if j >= 2:
                    name, teacher = lines[j - 2], lines[j - 1]
                    consumed.update((j - 2, j - 1))
                elif j == 1:
                    name, teacher = lines[0], ""
                    consumed.add(0)
                    res.doubts.append(f"k{weekday}{big}: 文本块只有“课程名+周次”，教师字段缺失 {lines!r}")
                else:
                    name, teacher = "", ""
                    res.doubts.append(f"k{weekday}{big}: 周次表达式前没有课程名 {lines!r}")
                room = ""
                if j + 1 < len(lines) and (j + 1) not in anchor_idx and looks_like_room(lines[j + 1]):
                    room = lines[j + 1]
                    consumed.add(j + 1)
                weeks, periods, parity = parse_week_expr(anchor)
                if any(p > periods_per_day for p in periods):
                    res.doubts.append(f"k{weekday}{big}: 节次 {periods} 超出每日 {periods_per_day} 节")
                if any(w > semester_weeks for w in weeks):
                    res.doubts.append(f"k{weekday}{big}: 周次 {weeks} 超出学期 {semester_weeks} 周")
                if big not in big_periods_for(periods):
                    res.doubts.append(
                        f"k{weekday}{big}: 单元格大节与方括号节次 {periods} 不一致（以方括号为准）"
                    )
                if not name:
                    res.doubts.append(f"k{weekday}{big}: 解析不出课程名，原始文本 {anchor!r}")
                    continue
                m = Meeting(name, teacher, room, weekday, periods, weeks, parity)
                if m.key() not in seen:
                    seen.add(m.key())
                    meetings.append(m)
                    if not room:
                        res.doubts.append(f"k{weekday}{big}: 教室未提供，需核对；不补造地点")
            leftovers = [ln for i, ln in enumerate(lines) if i not in consumed]
            if leftovers:
                res.doubts.append(f"k{weekday}{big}: 未归属的文本行 {leftovers!r}")

    if nonempty_cells == 0:
        raise ParseError(
            "EMPTY_GRID",
            "网格存在但没有任何带周次表达式的课程；可能是异常空页，不得据此清空课表",
        )

    res.meetings = sorted(meetings, key=lambda m: (m.weekday, m.periods, m.course, m.weeks))
    scope_fields = {}
    for key in ("xh", "xn", "xq_m"):
        values = doc.xpath(f"//input[@name='{key}']/@value")
        if len(values) == 1 and values[0].strip():
            scope_fields[key] = values[0].strip()
    if len(scope_fields) == 3:
        res.source_scope = (hashlib.sha256(scope_fields["xh"].encode()).hexdigest(), scope_fields["xn"], scope_fields["xq_m"])
    res.identity = extract_identity(text, mask=mask)
    res.unscheduled = extract_unscheduled(text)
    res.doubts.extend(pending)

    scheduled_names = {m.course for m in res.meetings}
    for row in res.unscheduled:
        if row.get("课程") and row["课程"] not in scheduled_names:
            res.doubts.append(f"课程清单中的“{row['课程']}”未排入网格（无固定星期/节次，需人工确认）")

    res.stats = {
        "cells": len(grid_cells),
        "nonempty_cells": nonempty_cells,
        "meetings": len(res.meetings),
        "courses": len(scheduled_names),
        "unscheduled_rows": len(res.unscheduled),
        "week_range": [min((m.weeks[0] for m in res.meetings), default=None),
                       max((m.weeks[-1] for m in res.meetings), default=None)],
        "semester_weeks": semester_weeks,
        "identity_present": has_identity,
        "unscheduled_table_present": has_unscheduled,
    }
    return res


def extract_identity(text: str, *, mask: bool = True) -> dict[str, str]:
    out: dict[str, str] = {}
    tables = lxml_html.fromstring(text).xpath("//table")
    if not tables:
        return out
    plain = _text_of(tables[0])
    keys = ("本学期修读总学分", "所在班级", "课程门数", "课程学分", "环节学分", "学号", "姓名", "班级")
    labels = "|".join(map(re.escape, keys))
    pattern = re.compile(rf"({labels})\s*[:：]?\s*(.*?)(?=(?:{labels})\s*[:：]?|$)")
    for m in pattern.finditer(plain):
        k, v = m.group(1), m.group(2).strip()
        if k in ("课程门数", "课程学分", "环节学分", "本学期修读总学分"):
            numeric = re.match(r"\d+(?:\.\d+)?", v)
            v = numeric.group(0) if numeric else ""
        if k in out or not v:
            continue
        out[k] = _mask_value(k, v) if mask else v
    return out


def _mask_value(key: str, value: str) -> str:
    if key == "姓名":
        return value[0] + "*" * max(1, len(value) - 1)
    if key == "学号":
        return value[:4] + "*" * max(0, len(value) - 4) if len(value) > 4 else "***"
    if key in ("所在班级", "班级"):
        return value[:2] + "***" if len(value) > 2 else "***"
    return value


def extract_unscheduled(text: str) -> list[dict[str, str]]:
    """课程清单表：只可靠取出课程名与时间地点，其余列原样保留（脱敏留给 use-site）。"""
    rows: list[dict[str, str]] = []
    if lxml_html is None:
        return rows
    doc = lxml_html.fromstring(text)
    for t in doc.xpath("//table"):
        head = _text_of(t)
        if not all(h in head for h in ("上课班级代码", "选课状态")) and "修读性质" not in head:
            continue
        header: list[str] = []
        for tr in t.xpath(".//tr"):
            cells = [_text_of(td) for td in tr.xpath("./td|./th")]
            if cells and any("上课班级代码" in c or "修读性质" in c for c in cells):
                header = cells
                continue
            if not cells or not any(cells):
                continue
            if not header:
                continue
            row = {header[i] if i < len(header) else f"col{i}": c for i, c in enumerate(cells)}
            course = ""
            for k, v in row.items():
                if "课程" in k and v:
                    course = re.sub(r"\[.*?\]", "", v).strip()
                    break
            if course:
                row["课程"] = course
                rows.append(row)
    return rows


# --------------------------------------------------------------------------
# 比对与自检
# --------------------------------------------------------------------------
def compare(a: ParseResult, b: ParseResult) -> dict:
    ka = {m.key(): m for m in a.meetings}
    kb = {m.key(): m for m in b.meetings}
    only_a = sorted(k for k in ka if k not in kb)
    only_b = sorted(k for k in kb if k not in ka)
    return {
        "a": {"source": a.source, "sha256": a.sha256, "meetings": len(ka), "courses": a.stats.get("courses")},
        "b": {"source": b.source, "sha256": b.sha256, "meetings": len(kb), "courses": b.stats.get("courses")},
        "identical_file": a.sha256 == b.sha256,
        "identical_records": not only_a and not only_b,
        "only_in_a": [ka[k].display() for k in only_a],
        "only_in_b": [kb[k].display() for k in only_b],
        "proliferation": len(kb) > len(ka),
        "identity_and_term_verified": a.source_scope is not None and a.source_scope == b.source_scope,
        "source_mismatch": a.source_scope is not None and b.source_scope is not None and a.source_scope != b.source_scope,
    }


SYNTHETIC_EXPORT = """<html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
<table><tr><td>学号：</td><td>2024xxxxxx</td><td>姓名：</td><td>张三</td><td>所在班级：</td><td>计算机2401</td>
<td>课程门数：</td><td>3</td><td>本学期修读总学分：</td><td>8.5</td></tr>
<tr><td><input type="hidden" name="xn" value="2026"></td><td><input type="hidden" name="xq_m" value="0"></td></tr></table>
<table>
<tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td><td>星期六</td><td>星期日</td></tr>
<tr><td>第一大节</td>
<td id="k11"><div>高等数学<br>李四<br>1-2,5-13[1-2]<br>博学楼A101</div><div>大学英语<br>王五<br>2,6-8,12-14 双 [1-2]<br>博学楼B202</div></td>
<td id="k21">&nbsp;</td><td id="k31">&nbsp;</td><td id="k41">&nbsp;</td><td id="k51">&nbsp;</td><td id="k61">&nbsp;</td><td id="k71">&nbsp;</td></tr>
<tr><td>第二大节</td><td id="k12">&nbsp;</td><td id="k22">&nbsp;</td><td id="k32">&nbsp;</td><td id="k42">&nbsp;</td><td id="k52">&nbsp;</td><td id="k62">&nbsp;</td><td id="k72">&nbsp;</td></tr>
<tr><td>第三大节</td><td id="k13">&nbsp;</td><td id="k23">&nbsp;</td><td id="k33">&nbsp;</td><td id="k43">&nbsp;</td><td id="k53">&nbsp;</td><td id="k63">&nbsp;</td><td id="k73">&nbsp;</td></tr>
<tr><td>第四大节</td><td id="k14">&nbsp;</td><td id="k24">&nbsp;</td><td id="k34">&nbsp;</td><td id="k44">&nbsp;</td><td id="k54">&nbsp;</td><td id="k64">&nbsp;</td><td id="k74">&nbsp;</td></tr>
<tr><td>第五大节</td><td id="k15">&nbsp;</td><td id="k25">&nbsp;</td><td id="k35">&nbsp;</td><td id="k45">&nbsp;</td><td id="k55">&nbsp;</td><td id="k65">&nbsp;</td><td id="k75">&nbsp;</td></tr>
<tr><td>第六大节</td><td id="k16">&nbsp;</td><td id="k26">&nbsp;</td><td id="k36">&nbsp;</td>
<td id="k46"><div>专业实践<br>赵六<br>1,5,7,9,11,13,15 单 [11-12]<br>实验楼C303</div></td>
<td id="k56">&nbsp;</td><td id="k66">&nbsp;</td><td id="k76">&nbsp;</td></tr>
</table>
<table><tr><td>上课班级代码</td><td>课程[代码]</td><td>总学时</td><td>学分</td><td>修读性质</td><td>任课教师[工号]</td><td>选课状态</td><td>上课时间地点</td></tr>
<tr><td>DZ001</td><td>网络创业基础[TS0001]</td><td>16</td><td>1.0</td><td>选修</td><td>孙七[1001]</td><td>已选</td><td>2周/10周</td></tr></table>
</body></html>
"""


def self_test() -> int:
    import tempfile, os

    ok = True
    tmp = tempfile.mkdtemp(prefix="tt_selftest_")

    def w(name: str, content: str | bytes) -> str:
        p = os.path.join(tmp, name)
        open(p, "wb").write(content.encode("gbk") if isinstance(content, str) else content)
        return p

    def expect(code: str, path: str) -> None:
        nonlocal ok
        try:
            parse_export(path)
        except ParseError as e:
            if e.code == code:
                print(f"[PASS] {code} <- {os.path.basename(path)}")
            else:
                ok = False
                print(f"[FAIL] 期望 {code}，实际 {e.code} ({os.path.basename(path)})")
        else:
            ok = False
            print(f"[FAIL] 期望 {code}，但没有报错 ({os.path.basename(path)})")

    # 1) 正常解析 + 周次表达式 + 跨大节去重
    good = w("good.html", SYNTHETIC_EXPORT)
    r = parse_export(good)
    by_name = {m.course: m for m in r.meetings}
    math, eng = by_name.get("高等数学"), by_name.get("大学英语")
    checks = [
        (len(r.meetings) == 3, f"应解析出 3 条安排，实际 {len(r.meetings)}"),
        (math is not None and math.weeks == (1, 2, 5, 6, 7, 8, 9, 10, 11, 12, 13) and math.weekday == 1,
         f"1-2,5-13 应展开为 1,2,5..13 且为周一（实际 {math and (math.weeks, math.weekday)}）"),
        (math is not None and math.room == "博学楼A101" and math.teacher == "李四",
         "课程名/教师/教室切分正确"),
        (eng is not None and eng.parity == "双" and eng.weeks == (2, 6, 8, 12, 14),
         f"双周列表解析（实际 {eng and (eng.parity, eng.weeks)}）"),
        (any(m.parity == "单" and m.periods == (11, 12) for m in r.meetings), "单周 [11-12] 解析"),
        (len([d for d in r.doubts if "未排入网格" in d]) == 1, "未排课课程应产生疑点而非丢弃"),
        (r.stats["courses"] == 3, "课程数应为 3"),
        (r.identity.get("课程门数") == "3" and r.identity.get("本学期修读总学分") == "8.5", "课程数量与学分不混入邻接字段"),
        ({m.key() for m in parse_text(SYNTHETIC_EXPORT).meetings} == {m.key() for m in r.meetings}, "内存 HTML 与文件入口解析一致，无需导出文件"),
    ]
    for cond, msg in checks:
        print(("[PASS] " if cond else "[FAIL] ") + msg)
        ok &= cond

    for expr, expected in (
        ("5-14 双 [1-2]", (6, 8, 10, 12, 14)),
        ("5-14 单 [1-2]", (5, 7, 9, 11, 13)),
        ("5-6,12,17[1-2]", (5, 6, 12, 17)),
    ):
        cond = parse_week_expr(expr)[0] == expected
        print(("[PASS] " if cond else "[FAIL] ") + f"明确周次集合：{expr}")
        ok &= cond
    for expr in ("0-2[1-2]", "1-2[0-2]", "1 单 [1-2]".replace("单", "双")):
        try:
            parse_week_expr(expr)
        except ParseError as e:
            cond = e.code == "BAD_WEEK"
        else:
            cond = False
        print(("[PASS] " if cond else "[FAIL] ") + f"拒绝无效周次或节次：{expr}")
        ok &= cond
    unknown = SYNTHETIC_EXPORT.replace('<td id="k21">&nbsp;</td>', '<td id="k21">未知课程格式</td>')
    cond = any("有文本但未识别" in d for d in parse_export(w("unknown.html", unknown)).doubts)
    print(("[PASS] " if cond else "[FAIL] ") + "无法解析的非空单元格不能静默丢失")
    ok &= cond

    no_ids = re.sub(r' id="k\d\d"', '', SYNTHETIC_EXPORT)
    header_result = parse_export(w("header_grid.html", no_ids))
    cond = {m.key() for m in header_result.meetings} == {m.key() for m in r.meetings}
    print(("[PASS] " if cond else "[FAIL] ") + "无 k-ID 的星期表头格式与同内容网格结果一致")
    ok &= cond
    incomplete = no_ids.replace('<td>星期日</td>', '<td>未知列</td>')
    expect("INCOMPLETE_GRID", w("incomplete.html", incomplete))

    # 2) 跨大节重复条目去重：同一门课出现在 k11 与 k12，应只有一条
    cross = SYNTHETIC_EXPORT.replace(
        '<td id="k12">&nbsp;</td>',
        '<td id="k12"><div>高等数学<br>李四<br>1-2,5-13[1-2]<br>博学楼A101</div></td>',
    )
    r2 = parse_export(w("cross.html", cross))
    cond = len(r2.meetings) == 3
    print(("[PASS] " if cond else "[FAIL] ") + f"跨大节重复条目应折叠（3 条），实际 {len(r2.meetings)}")
    ok &= cond

    # 3) 两次解析同一文件结果一致
    c = compare(parse_export(good), parse_export(good))
    cond = c["identical_records"] and c["identical_file"] and not c["proliferation"]
    print(("[PASS] " if cond else "[FAIL] ") + "同一文件两次解析结果一致且不增生")
    ok &= cond
    a_scope = ParseResult("a", "a", source_scope=("same-account", "2026", "0"))
    b_scope = ParseResult("b", "b", source_scope=("same-account", "2026", "1"))
    cond = compare(a_scope, b_scope)["source_mismatch"]
    print(("[PASS] " if cond else "[FAIL] ") + "相同课程内容不能掩盖来源学期不同")
    ok &= cond

    # 4) 失败模式
    expect("SESSION_LOST", w("lost.html", "<script>alert('温馨提示：凭证已失效，请重新登录!');window.top.location.href='/sdnzjw/';</script>"))
    empty_grid = re.sub(r'(<t[dh] id="k\d\d">).*?(</t[dh]>)', r"\1&nbsp;\2", SYNTHETIC_EXPORT, flags=re.S)
    expect("EMPTY_GRID", w("empty.html", empty_grid))
    expect("TRUNCATED", w("trunc.html", SYNTHETIC_EXPORT[:1200]))
    expect("NOT_HTML", w("nothtml.html", "这是一段普通文本，不是网页内容。" + "x" * 3000))
    expect("NO_GRID", w("nogrid.html", re.sub(r'id="k(\d)(\d)"', r'id="z\1\2"', SYNTHETIC_EXPORT).replace("星期", "未知")))

    print("SELFTEST=" + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="山东女子学院课表导出解析（最小验证）")
    ap.add_argument("file", nargs="?", help="导出文件路径")
    ap.add_argument("other", nargs="?", help="--compare 时的第二个导出文件")
    ap.add_argument("--json", dest="json_out", help="把解析结果写入 JSON")
    ap.add_argument("--semester-weeks", type=int, default=19)
    ap.add_argument("--periods", type=int, default=12)
    ap.add_argument("--no-mask", action="store_true", help="输出完整身份字段（默认脱敏）")
    ap.add_argument("--compare", action="store_true", help="比较两次导出，检查是否增生课程")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--summary", action="store_true", help="仅输出计数和状态，不输出私人课表或身份")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    if not args.file:
        ap.error("需要导出文件路径，或使用 --self-test")

    try:
        a = parse_export(args.file, semester_weeks=args.semester_weeks,
                         periods_per_day=args.periods, mask=not args.no_mask)
        if args.compare:
            if not args.other:
                ap.error("--compare 需要两个导出文件")
            b = parse_export(args.other, semester_weeks=args.semester_weeks,
                             periods_per_day=args.periods, mask=not args.no_mask)
            cmp = compare(a, b)
            if cmp["source_mismatch"]:
                raise ParseError("SOURCE_MISMATCH", "两份结果的来源账号或学期不同，不能作为重复获取对比")
            if args.summary:
                print(json.dumps({
                    "identical_file": cmp["identical_file"],
                    "identical_records": cmp["identical_records"],
                    "meeting_counts": [len(a.meetings), len(b.meetings)],
                    "unscheduled_equal": a.unscheduled == b.unscheduled,
                    "doubt_counts": [len(a.doubts), len(b.doubts)],
                    "identity_and_term_verified": cmp["identity_and_term_verified"],
                    "source_mismatch": cmp["source_mismatch"],
                }, ensure_ascii=False))
                return 0 if cmp["identical_records"] and a.unscheduled == b.unscheduled and cmp["identity_and_term_verified"] else 1
            print(json.dumps(cmp, ensure_ascii=False, indent=2))
            print("COMPARE=" + ("IDENTICAL" if cmp["identical_records"] else "DIFFERENT"))
            if cmp["proliferation"]:
                print("WARNING=第二次获取记录数多于第一次，可能增生")
            return 0 if cmp["identical_records"] else 1
    except ParseError as e:
        print(f"ERROR={e.code} {e.detail}", file=sys.stderr)
        return 2

    if args.summary:
        print("STATS=" + json.dumps(a.stats, ensure_ascii=False))
        print(f"DOUBTS={len(a.doubts)}")
        print("RESULT=" + ("REVIEW_REQUIRED" if a.doubts else "PARSED_NOT_VERIFIED_COMPLETE"))
        return 0

    print(f"SOURCE={a.source}")
    print(f"SHA256={a.sha256}")
    print("IDENTITY=" + json.dumps(a.identity, ensure_ascii=False))
    print("STATS=" + json.dumps(a.stats, ensure_ascii=False))
    print(f"MEETINGS={len(a.meetings)}")
    for i, m in enumerate(a.meetings, 1):
        print(f"  {i:3d}. {m.display()}")
    if a.unscheduled:
        print(f"UNSCHEDULED={len(a.unscheduled)}")
        for row in a.unscheduled:
            print(f"  - {row.get('课程','?')} | {row.get('上课时间地点','?')} | {row.get('任课教师[工号]', row.get('任课教师','?'))}")
    if a.doubts:
        print(f"DOUBTS={len(a.doubts)}")
        for d in a.doubts:
            print(f"  ! {d}")
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(a.to_json(), fh, ensure_ascii=False, indent=2)
        print(f"JSON={args.json_out}")
    print("RESULT=OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
