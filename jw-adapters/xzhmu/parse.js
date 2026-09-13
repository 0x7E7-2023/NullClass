(function () {
    // 徐州医科大学研究生教务（xzhmu.edu.cn）适配器 —— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 XZHMU/xzhmu_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 commit e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游适配器名「徐州医科大学研究生教务」（adapters.yaml 里 category=POSTGRADUATE），
    // 登录走学校统一身份认证（authserver.xzhmu.edu.cn），课表在 ehall.xzhmu.edu.cn 一侧。
    //
    // 平台判定说明（重要）：上游脚本**不请求任何接口**（全文没有 fetch / XMLHttpRequest），
    // 它只读页面上已经画好的 table#kb.curriculum，所以「按接口路径确认平台」在这件上
    // 无从谈起 —— 上游注释与批次文档都写「强智 eams」，但脚本本身给不出接口证据。
    // 能确认的只有：课表是一张真正的 <table>（不是青果那种 canvas / 图片），走 DOM
    // 解析即可，不需要 OCR。这一条只影响说明文字，不影响下面的算法。
    // 上游脚本也没有本科生 / 研究生双分支（只有一条 DOM 读取路径），因此不存在
    // 「移植哪个分支、另一个进 warnings」的问题。
    //
    // 移植改动：
    //   ① 上游在页面里一边读 DOM 一边算周次、合并去重；这里只做转换，输入是 extract.js
    //      交出来的格子清单（所以 CI 能用 Rhino 跑真实回归）。
    //   ② 星期先看**格子自带的星期属性**（上游读的 td[w]）—— 它是页面自己声明的，单元格
    //      被 rowSpan 挤走列号时它仍然是对的；没有属性才退回「网格列号 → 星期」的星期表头
    //      映射（本批检查表第 3 条要的就是这条，列号由 extract.js 按 rowSpan/colSpan 算准）。
    //      两条都拿不到 → 该格进 warnings，**不按 td 下标或行内格子个数猜列**。
    //   ③ 上游的 parseWeeks 会把写在范围前面的单双周标记（「(单)1-16周」）丢掉，整段塌成
    //      「每周都上」而且不报警；这里四种写法都认（标记在「周」后 / 在范围前 / 在括号里
    //      的「单周」/ 多段混排），标记只出现在部分段上时另出一条核对提示。
    //   ④ 上游的节次只认「格子文本末尾的数字」，于是「第1节 08:00-08:40」这种标签会读出
    //      45 这种数；这里按「第N节」→「行首数字」→「与内置作息表的开始时间对齐」的顺序
    //      认，认不出来进 warnings，不静默丢课。
    //   ⑤ 合并去重沿用上游的两段式（先并相邻节次，再对同一节次求周次并集），但按
    //      「课名 + 教师 + 地点」分组：一门课一个 courses[] 条目、多个 blocks（我们的载荷
    //      形态本来就是这样，上游是一时段一条记录）。
    //   ⑥ 教师 / 地点为空时留空（上游的 teacher 因为一个 TreeWalker 用法的 bug 恒为空，
    //      extract.js 已经修好，这里原样采用，取不到就留空 —— 不写「未知」）。
    //   ⑦ 开学日教务不给（上游只存了作息表），只能推算：优先用页面上 / 用户给的
    //      「现在第几周」反推，拿不到就按最近的周一，两种情况都写进 warnings。
    //   ⑧ warnings 顺序固定：解析失败类 → 定位失败类 → 推算类 → 作息表类。
    //   ⑨ 周次文本里混进来的**非周次**数字要排掉（批次三检查表第 2 条）：括号里只有数字 / 区间的
    //      整组（教学班序号「(1)」「(1-2)」）在解析周次**之前**先整组删掉，带「节」的分段
    //      （「第9-10节」）是节次来源、也不参与周次。不排就会静默读错：「(1-2)第2-8周」读成
    //      第 1-2 周、「3-16周,(1)」凭空多出第 1 周、「第9-10节 第5-8周」读成第 9-10 周。
    //      排完认不出周次的格子进 warnings，不硬凑一个周次。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];

    var MAX_WARNINGS = 20;
    var MAX_WARNING_TEXT = 200;
    var DEFAULT_TOTAL_WEEKS = 20;
    var MAX_TOTAL_WEEKS = 30;
    var MAX_PERIOD = 13;
    var TERM_NAME_PREFIX = '徐州医科大学 ';
    var SEMESTER_NAME_RE = /20\d{2}\s*[-—~至]\s*20\d{2}\s*学年\s*第?\s*[0-9一二三四五六七八九]{1,2}\s*学期/;

    // 星期属性 / 表头文字里的星期。只认「星期X」「周X」「Monday」这类**词**：
    // 单个数字不认 —— 万一同名属性真是别的意思（宽度之类），认成星期会把整列课对错。
    var DAY_WORDS = {
        monday: 1, tuesday: 2, wednesday: 3, thursday: 4, friday: 5, saturday: 6, sunday: 7,
        mon: 1, tue: 2, tues: 2, wed: 3, thu: 4, thur: 4, thurs: 4, fri: 5, sat: 6, sun: 7
    };
    var CN_DAY = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };

    // 该校作息表（13 节），出自上游适配器内置的 PRESET_TIME_SLOTS。上游把它当「预设作息
    // 时间」导入，这里作为 periodTimes 交出去，并在 warnings 里说明来源。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:40' },
        { periodIndex: 2, start: '08:50', end: '09:30' },
        { periodIndex: 3, start: '09:40', end: '10:20' },
        { periodIndex: 4, start: '10:30', end: '11:10' },
        { periodIndex: 5, start: '11:20', end: '12:00' },
        { periodIndex: 6, start: '14:00', end: '14:40' },
        { periodIndex: 7, start: '14:50', end: '15:30' },
        { periodIndex: 8, start: '15:40', end: '16:20' },
        { periodIndex: 9, start: '16:30', end: '17:10' },
        { periodIndex: 10, start: '17:20', end: '18:00' },
        { periodIndex: 11, start: '19:00', end: '19:40' },
        { periodIndex: 12, start: '19:50', end: '20:30' },
        { periodIndex: 13, start: '20:40', end: '21:20' }
    ];

    var warnings = [];

    // 每条 ≤200 字、总数 ≤20 条 —— 超了整包会被应用拒掉（不是截断提示，是导入失败）
    function warn(message) {
        var msg = text(message);
        if (msg.length > MAX_WARNING_TEXT) msg = msg.slice(0, MAX_WARNING_TEXT - 1) + '…';
        if (!msg) return;
        if (warnings.length >= MAX_WARNINGS) return;
        if (warnings.indexOf(msg) >= 0) return;
        warnings.push(msg);
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
    }

    // 往提示里塞页面原文时先截断：整条提示上限 200 字，撑爆了会把「请核对课表」
    // 这种有用的半句挤掉（页面文字什么时候变长不由我们决定）
    function clip(value, max) {
        var s = tidy(value);
        return s.length > max ? s.slice(0, max) + '…' : s;
    }

    function intOf(value, fallback) {
        var n = parseInt(value, 10);
        return isNaN(n) ? fallback : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    function parseIso(value) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text(value));
        if (!m) return null;
        return new Date(intOf(m[1], 1970), intOf(m[2], 1) - 1, intOf(m[3], 1));
    }

    function shiftDays(date, days) {
        return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
    }

    function mondayIso(date) {
        return isoOf(shiftDays(date, -((date.getDay() + 6) % 7)));
    }

    // 排序一律用码点比较，不用 localeCompare：同一份脚本要在 JVM 的 Rhino 与 WebView
    // 两个引擎里给出同样的顺序，中文的 locale 排序两边不一定一致。
    function compareText(a, b) {
        if (a < b) return -1;
        if (a > b) return 1;
        return 0;
    }

    function uniqueSorted(weeks) {
        var seen = {};
        var out = [];
        var i;
        for (i = 0; i < weeks.length; i++) {
            var w = weeks[i];
            if (w >= 1 && !seen[w]) {
                seen[w] = true;
                out.push(w);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // ---------------------------------------------------------------- 星期

    // 「星期属性」与「表头文字」共用一个识别函数：只认星期词（Monday / Mon / 星期一 / 周一）
    function dayOfValue(value) {
        var s = tidy(value);
        if (!s) return 0;
        var lower = s.toLowerCase();
        if (DAY_WORDS[lower]) return DAY_WORDS[lower];
        var m = /(?:星期|周|礼拜)\s*([1-7一二三四五六日天])/.exec(s);
        if (m) return CN_DAY[m[1]] || 0;
        return 0;
    }

    // 星期表头行：一行里有 ≥3 个格子写着星期词才认（认第一行这样的），给出「网格列号 → 星期」
    function headerDayMap() {
        var map = {};
        var i;
        var j;
        for (i = 0; i < rows.length; i++) {
            var list = rows[i].cells || [];
            var hits = [];
            for (j = 0; j < list.length; j++) {
                var day = dayOfValue(list[j].text);
                if (day) hits.push({ col: list[j].col, day: day });
            }
            if (hits.length < 3) continue;
            for (j = 0; j < hits.length; j++) map[hits[j].col] = hits[j].day;
            break;
        }
        return map;
    }

    // 一个格子是星期几：**先看格子自带的星期属性**（上游只认这一条，页面自己声明的，
    // 单元格被 rowSpan 挤开列号时它仍然是对的），再退回「网格列号 → 星期」的表头映射。
    // 两条都没有就是这个格子定位不了 —— 调用方必须进 warnings，不许按行宽 / td 下标猜。
    function dayOfCell(cell, headerMap) {
        var own = dayOfValue(cell.day);
        if (own) return own;
        return headerMap[cell.col] || 0;
    }

    // ---------------------------------------------------------------- 节次

    // 节次标签：「第1节」「第1-2节」「1」「1 08:00-08:40」「08:00-08:40」都可能。
    // 返回 { tokens: [节次...] }；认不出来返回 null（调用方必须为此出声，别静默丢课）。
    function sectionLabelOf(labelText) {
        var s = tidy(labelText);
        if (!s) return null;
        var m = /第\s*(\d{1,3})\s*(?:[-—~至]\s*(\d{1,3})\s*)?节/.exec(s);
        var start;
        var end;
        var i;
        if (m) {
            start = intOf(m[1], 0);
            end = m[2] ? intOf(m[2], 0) : start;
            return tokensOf(start, end);
        }
        // 行首数字（「1」「01」「1 08:00-08:40」）；后面紧跟数字或冒号的都不算
        // （那是时间「08:00-08:40」的一部分，别把 08 读成第 8 节）
        m = /^\s*(\d{1,3})(?![\d:])\s*(?:[-—~至]\s*(\d{1,3}))?/.exec(s);
        if (m) {
            start = intOf(m[1], 0);
            end = m[2] ? intOf(m[2], 0) : start;
            return tokensOf(start, end);
        }
        // 只有时间：拿开始时间到内置作息表里对号
        m = /(\d{1,2})\s*[:：]\s*(\d{2})/.exec(s);
        if (m) {
            var minutes = intOf(m[1], -1) * 60 + intOf(m[2], -1);
            for (i = 0; i < PERIOD_TIMES.length; i++) {
                if (minutesOf(PERIOD_TIMES[i].start) === minutes) return { tokens: [PERIOD_TIMES[i].periodIndex] };
            }
        }
        return null;
    }

    function tokensOf(start, end) {
        if (!(start >= 1)) return null;
        if (!(end >= start)) end = start;
        var tokens = [];
        for (var w = start; w <= end && w - start < 60; w++) tokens.push(w);
        return tokens.length ? { tokens: tokens } : null;
    }

    function minutesOf(hhmm) {
        var m = /^(\d{1,2}):(\d{2})$/.exec(text(hhmm));
        if (!m) return -1;
        return intOf(m[1], 0) * 60 + intOf(m[2], 0);
    }

    // 行号 → 节次。标签格自己 rowSpan 盖住几行时，标签里的节次按顺序往后发
    // （「第5-6节」盖两行 → 第 5、6 节；标签只写「第5节」却盖两行 → 第 5、6 节）。
    function buildSectionMap() {
        var map = {};
        var i;
        var j;
        for (i = 0; i < rows.length; i++) {
            var cells = (rows[i].cells || []).slice();
            cells.sort(function (a, b) { return (a.col || 0) - (b.col || 0); });
            if (!cells.length) continue;
            if (cells[0].col !== 0 || cells[0].hidden) continue;
            var parsed = sectionLabelOf(cells[0].text);
            if (!parsed) continue;
            var tokens = parsed.tokens;
            var rowSpan = cells[0].rowSpan > 1 ? cells[0].rowSpan : 1;
            for (j = 0; j < rowSpan; j++) {
                var value = j < tokens.length ? tokens[j] : tokens[tokens.length - 1] + (j - tokens.length + 1);
                if (value >= 1 && !map[i + j]) map[i + j] = value;
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- 周次

    // 周次文本 → { weeks: [...], ambiguous: 标记只出现在部分段上 }。
    // 认得的写法：1-16周 / 1-16 / 第1-5周 / 1-16周(单) / (单)1-16周 / 1-16(单周) / 1,3,5周 /
    //             1-3,5-9周 / 1-3周 5-9周（空白分段）/ 双周2-6、10周 / 单周1-9
    // 上游（以及它的同族）在这里翻过车：标记写在范围**前面**时会被丢掉，整段塌成每周都上。
    function parseWeeks(source) {
        var s = text(source);
        s = s.replace(/（/g, '(').replace(/）/g, ')').replace(/第/g, '');
        // 统一口径第一步：只把区间分隔符**连同两侧空白**归一成半角连字符 —— 全角波浪
        // ～(U+FF5E)、全角减 －(U+FF0D)、数学减 −(U+2212)、en dash –、em dash —、
        // 半角 ~、汉字「至 / 到」。**不许全文删空白**：空白在本域里也是分段符
        // （"1-3周 5-9周"），全删会并成 "1-35-9"，静默吞掉一段（5-9 变成 35 的一部分）。
        s = s.replace(/\s*[-—–−－~～至到]\s*/g, '-');
        // 逗号一类的分段符折成半角逗号（上面的归一只动区间分隔符，不动分段符）
        s = s.replace(/[，、；;]/g, ',');
        // ① 括号里**只有数字 / 区间**的（「(1)」「(1-2)」，全角括号上一步已折成半角）整组先删掉：
        //    那是教学班序号一类的选中标记，不是周次来源（括号里带「单 / 双」的不动 —— 那是周次标记，
        //    见下面的「(单)1-16周」）。不删就会出事：「(1-2)第2-8周」会先命中 (1-2)，把 1-2 当周次；
        //    「3-16周,(1)」会凭空多出一个第 1 周。
        s = s.replace(/\((?:\d{1,3}(?:[-—~至]\d{1,3})?)(?:,(?:\d{1,3}(?:[-—~至]\d{1,3})?))*\)/g, '');
        // ② 带「节」的「9-10节」「1-2节」是**节次**来源（节次由 sectionLabelOf 那条路径处理），
        //    取周次时整段删掉，否则「第9-10节 第5-8周」会把 9-10 当成周次。
        s = s.replace(/\d{1,3}(?:[-—~至]\d{1,3})?节/g, '');
        if (!s) return { weeks: [], ambiguous: false };

        var globalType = '';
        // 开头的全局单双标记（空白不再全文删掉，正则两侧要自己容忍空白）
        var head = /^\s*\(?\s*([单双])\s*周?\s*\)?\s*/.exec(s);
        if (head) {
            globalType = head[1];
            s = s.slice(head[0].length);
        }
        if (!s) return { weeks: [], ambiguous: false };

        // 空白与逗号都是分段符（统一口径第二步：剩下的空白当分段符，不删）
        var parts = s.split(/[\s,]+/);
        var nonEmpty = 0;
        var marked = 0;
        var i;
        var j;
        var weeks = [];
        for (i = 0; i < parts.length; i++) {
            if (!parts[i]) continue;
            nonEmpty++;
            if (/[单双]/.test(parts[i])) marked++;
        }
        for (i = 0; i < parts.length; i++) {
            var part = parts[i];
            if (!part) continue;
            var marker = /([单双])/.exec(part);
            var type = marker ? marker[1] : globalType;
            var clean = part.replace(/单周|双周/g, '').replace(/[单双]/g, '').replace(/周/g, '');
            var range = /(\d{1,3})\s*[-—~至]\s*(\d{1,3})/.exec(clean);
            var start;
            var end;
            if (range) {
                start = intOf(range[1], 0);
                end = intOf(range[2], 0);
            } else {
                var single = /(\d{1,3})/.exec(clean);
                if (!single) continue;
                start = intOf(single[1], 0);
                end = start;
            }
            if (end < start) {
                var swap = start;
                start = end;
                end = swap;
            }
            if (start < 1) continue;
            if (end - start > 120) end = start + 120;
            for (j = start; j <= end; j++) {
                if (type === '单' && j % 2 === 0) continue;
                if (type === '双' && j % 2 === 1) continue;
                weeks.push(j);
            }
        }
        return {
            weeks: uniqueSorted(weeks),
            ambiguous: nonEmpty > 1 && marked > 0 && marked < nonEmpty
        };
    }

    // 周次集合 → 极大段（相邻用 ALL，隔周用 ODD / EVEN，落单的一周也是 ALL）
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j] };
            if (run.start === run.end || step === 1) {
                run.weekType = 'ALL';
            } else {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // ---------------------------------------------------------------- 格子

    var stats = {
        unparsedBlocks: 0,
        noWeekBlocks: 0,
        droppedWeeks: 0,
        unpositionedDay: 0,
        unpositionedSection: 0,
        colSpanCells: 0,
        extraSpans: 0
    };
    var ambiguousTexts = [];

    // 一个格子里的一段课：课名 / 周次 / 地点 + 夹在周次与地点之间的教师名
    function blockOf(raw) {
        var spans = raw.spans || [];
        if (spans.length < 3) {
            stats.unparsedBlocks++;
            return null;
        }
        var name = tidy(spans[0]);
        var weeksText = tidy(spans[1]);
        var location = tidy(spans[2]);
        if (!name || !weeksText) {
            stats.unparsedBlocks++;
            return null;
        }
        if (spans.length > 3) stats.extraSpans++;
        var parsed = parseWeeks(weeksText);
        if (parsed.ambiguous && ambiguousTexts.length < 3 && ambiguousTexts.indexOf(weeksText) < 0) {
            ambiguousTexts.push(weeksText);
        }
        var kept = [];
        for (var i = 0; i < parsed.weeks.length; i++) {
            if (parsed.weeks[i] > MAX_TOTAL_WEEKS) {
                stats.droppedWeeks++;
                continue;
            }
            kept.push(parsed.weeks[i]);
        }
        if (!kept.length) {
            stats.noWeekBlocks++;
            return null;
        }
        return { name: name, teacher: tidy(raw.teacher), location: location, weeks: kept };
    }

    function collectRecords(headerMap, sectionMap) {
        var records = [];
        var covered = {};
        var i;
        var j;
        var k;
        for (i = 0; i < rows.length; i++) {
            var cells = (rows[i].cells || []).slice();
            cells.sort(function (a, b) { return (a.col || 0) - (b.col || 0); });
            for (j = 0; j < cells.length; j++) {
                var cell = cells[j];
                var rowSpan = cell.rowSpan > 1 ? cell.rowSpan : 1;
                var colSpan = cell.colSpan > 1 ? cell.colSpan : 1;
                var key = i + ':' + cell.col;
                var wasCovered = covered[key] === true;
                var dr;
                var dc;
                if (!wasCovered) {
                    for (dr = 0; dr < rowSpan; dr++) {
                        for (dc = 0; dc < colSpan; dc++) covered[(i + dr) + ':' + (cell.col + dc)] = true;
                    }
                }
                // 被上面 rowSpan 盖住的格子（页面常留着同内容的占位格）与 display:none 的
                // 格子都跳过 —— 跳过的原因不是「解析失败」，不进 warnings
                if (wasCovered || cell.hidden) continue;
                var blocks = cell.blocks || [];
                if (!blocks.length) continue;
                if (colSpan > 1) stats.colSpanCells++;

                var parsed = [];
                for (k = 0; k < blocks.length; k++) {
                    var block = blockOf(blocks[k]);
                    if (block) parsed.push(block);
                }
                if (!parsed.length) continue;

                var day = dayOfCell(cell, headerMap);
                var section = sectionMap[i] || 0;
                if (!day) {
                    stats.unpositionedDay++;
                    continue;
                }
                if (!section) {
                    stats.unpositionedSection++;
                    continue;
                }
                for (k = 0; k < parsed.length; k++) {
                    records.push({
                        name: parsed[k].name,
                        teacher: parsed[k].teacher,
                        location: parsed[k].location,
                        day: day,
                        startPeriod: section,
                        endPeriod: section + rowSpan - 1,
                        weeks: parsed[k].weeks
                    });
                }
            }
        }
        return records;
    }

    // ---------------------------------------------------------------- 合并

    function weeksKeyOf(weeks) {
        return weeks.join(',');
    }

    function sameSlot(a, b) {
        return a.day === b.day && a.startPeriod === b.startPeriod && a.endPeriod === b.endPeriod;
    }

    // 上游 mergeAndDistinctCourses 的两段式：
    //   ① 同一门课、同一周次、节次相接（上一段 end + 1 === 下一段 start）→ 并成一段
    //   ② 同一门课、同一节次段、周次不同 → 周次求并集
    function mergeSlots(list) {
        var i;
        var sorted = list.slice();
        sorted.sort(function (a, b) {
            return (a.day - b.day) ||
                (a.startPeriod - b.startPeriod) ||
                (a.endPeriod - b.endPeriod) ||
                compareText(weeksKeyOf(a.weeks), weeksKeyOf(b.weeks));
        });
        var step1 = [];
        var cur = null;
        for (i = 0; i < sorted.length; i++) {
            var rec = sorted[i];
            if (cur && cur.day === rec.day && weeksKeyOf(cur.weeks) === weeksKeyOf(rec.weeks)) {
                if (cur.endPeriod + 1 === rec.startPeriod) {
                    cur.endPeriod = rec.endPeriod;
                    continue;
                }
                if (cur.startPeriod === rec.startPeriod && cur.endPeriod === rec.endPeriod) continue;
            }
            if (cur) step1.push(cur);
            cur = {
                day: rec.day,
                startPeriod: rec.startPeriod,
                endPeriod: rec.endPeriod,
                weeks: rec.weeks.slice()
            };
        }
        if (cur) step1.push(cur);

        var step2 = [];
        var cur2 = null;
        for (i = 0; i < step1.length; i++) {
            var it = step1[i];
            if (cur2 && sameSlot(cur2, it)) {
                cur2.weeks = uniqueSorted(cur2.weeks.concat(it.weeks));
                continue;
            }
            if (cur2) step2.push(cur2);
            cur2 = { day: it.day, startPeriod: it.startPeriod, endPeriod: it.endPeriod, weeks: it.weeks.slice() };
        }
        if (cur2) step2.push(cur2);
        return step2;
    }

    function groupCourses(records) {
        var order = [];
        var groups = {};
        var KEY_SEP = String.fromCharCode(1);
        var i;
        for (i = 0; i < records.length; i++) {
            var rec = records[i];
            // 分隔符用控制字符，避免「课名 + 教师」拼出别人的键
            // （源码里不写显式的转义序，免得被工具链落成真控制字符）
            var key = rec.name + KEY_SEP + rec.teacher + KEY_SEP + rec.location;
            if (!groups[key]) {
                groups[key] = { name: rec.name, teacher: rec.teacher, location: rec.location, list: [] };
                order.push(key);
            }
            groups[key].list.push(rec);
        }
        order.sort(function (a, b) {
            var ga = groups[a];
            var gb = groups[b];
            return compareText(ga.name, gb.name) ||
                compareText(ga.teacher, gb.teacher) ||
                compareText(ga.location, gb.location);
        });

        var courses = [];
        for (i = 0; i < order.length; i++) {
            var group = groups[order[i]];
            var merged = mergeSlots(group.list);
            var blocks = [];
            var m;
            var r;
            for (m = 0; m < merged.length; m++) {
                var runs = runsOf(merged[m].weeks);
                for (r = 0; r < runs.length; r++) {
                    blocks.push({
                        dayOfWeek: merged[m].day,
                        startPeriod: merged[m].startPeriod,
                        endPeriod: merged[m].endPeriod,
                        startWeek: runs[r].start,
                        endWeek: runs[r].end,
                        weekType: runs[r].weekType,
                        location: group.location
                    });
                }
            }
            blocks.sort(function (a, b) {
                return (a.dayOfWeek - b.dayOfWeek) ||
                    (a.startPeriod - b.startPeriod) ||
                    (a.endPeriod - b.endPeriod) ||
                    (a.startWeek - b.startWeek) ||
                    (a.endWeek - b.endWeek) ||
                    compareText(a.weekType, b.weekType);
            });
            courses.push({ name: group.name, teacher: group.teacher, note: null, blocks: blocks });
        }
        return courses;
    }

    // ---------------------------------------------------------------- 学期

    function academicTermName(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        if (month >= 9 || month === 1) {
            var start = month === 1 ? year - 1 : year;
            return start + '-' + (start + 1) + '学年第一学期';
        }
        return (year - 1) + '-' + year + '学年第二学期';
    }

    function termNameFromPage(title, labels) {
        var candidates = [];
        var i;
        for (i = 0; i < labels.length; i++) candidates.push(labels[i]);
        candidates.push(title);
        for (i = 0; i < candidates.length; i++) {
            var match = SEMESTER_NAME_RE.exec(tidy(candidates[i]));
            if (match) return tidy(match[0]);
        }
        return '';
    }

    // ---------------------------------------------------------------- 主流程

    var headerMap = headerDayMap();
    var sectionMap = buildSectionMap();
    var records = collectRecords(headerMap, sectionMap);
    var courses = groupCourses(records);

    var maxWeek = 0;
    var i;
    var j;
    for (i = 0; i < records.length; i++) {
        for (j = 0; j < records[i].weeks.length; j++) {
            if (records[i].weeks[j] > maxWeek) maxWeek = records[i].weeks[j];
        }
    }
    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_TOTAL_WEEKS) totalWeeks = MAX_TOTAL_WEEKS;

    // 用到的节次里超出内置作息表的（内置 13 节，表里的时间给不出来）
    var untimed = [];
    var maxUsed = 0;
    for (i = 0; i < courses.length; i++) {
        for (j = 0; j < courses[i].blocks.length; j++) {
            if (courses[i].blocks[j].endPeriod > maxUsed) maxUsed = courses[i].blocks[j].endPeriod;
        }
    }
    for (i = MAX_PERIOD + 1; i <= maxUsed && untimed.length < 10; i++) untimed.push(i);

    // ---- warnings：顺序固定（解析失败类 → 定位失败类 → 推算类 → 作息表类）----
    if (!courses.length) {
        warn('课表里没有解析出任何课程（页面结构可能变了），请确认已打开「个人课表」页面、点了查询、课表已经画出来');
    }
    if (stats.unparsedBlocks) {
        warn('有 ' + stats.unparsedBlocks + ' 处课程格子没能解析出课程（课名 / 周次 / 地点不齐），已跳过，请核对课表');
    }
    if (stats.noWeekBlocks) {
        warn('有 ' + stats.noWeekBlocks + ' 处课程格子没能解析出周次，已跳过，请核对课表');
    }
    if (stats.droppedWeeks) {
        warn('课表里出现了超过 ' + MAX_TOTAL_WEEKS + ' 周的周次，已忽略超出部分，请核对课表');
    }
    if (stats.unpositionedDay) {
        warn('有 ' + stats.unpositionedDay + ' 格课没能定位到星期（格子上没有星期属性、也没有能对上的星期表头），已跳过，请核对课表');
    }
    if (stats.unpositionedSection) {
        warn('有 ' + stats.unpositionedSection + ' 格课没能定位到节次（所在行读不出节次），已跳过，请核对课表');
    }
    if (stats.colSpanCells) {
        warn('有 ' + stats.colSpanCells + ' 个课表格子横跨多列（colspan），已只按最左边那一列解析，请核对课表');
    }
    if (stats.extraSpans) {
        warn('有 ' + stats.extraSpans + ' 处课程格子出现了第 4 个字段，已按「课名 / 周次 / 地点」前三个字段解析，请核对课表');
    }
    if (ambiguousTexts.length) {
        var samples = [];
        for (i = 0; i < ambiguousTexts.length; i++) samples.push('「' + clip(ambiguousTexts[i], 40) + '」');
        warn('周次 ' + samples.join('、') + ' 里的单双周标记只出现在部分段上，已按只作用于本段处理，请核对课表');
    }

    var now = parseIso(data.today) || new Date();
    var currentWeek = intOf(data.currentWeek, 0);
    var weekSource = text(data.currentWeekSource);
    var firstDay = mondayIso(now);
    if (currentWeek >= 1 && currentWeek <= MAX_TOTAL_WEEKS) {
        firstDay = isoOf(shiftDays(parseIso(firstDay), -(currentWeek - 1) * 7));
        if (weekSource === 'page') {
            warn('开学日期教务没有提供，已按页面上周次选择器显示的「第 ' + currentWeek + ' 周」反推为 ' + firstDay + '，请在学期管理里核对');
        } else {
            warn('开学日期教务没有提供，已按你填的「第 ' + currentWeek + ' 周」反推为 ' + firstDay + '，请在学期管理里核对');
        }
    } else {
        warn('开学日期教务没有提供，已按最近的周一（' + firstDay + '）推算，请在学期管理里核对');
    }

    var termName = termNameFromPage(data.title, data.semesterLabels || []);
    if (!termName) {
        termName = TERM_NAME_PREFIX + academicTermName(now);
        warn('学期名教务没有提供，已按导入日期推算为「' + termName + '」，如与实际不符可在学期管理里改名');
    }

    if (maxWeek === 0) {
        warn('课表里没有可用的周次，学期总周数已按 ' + totalWeeks + ' 周计，如校历不同请在学期管理里调整');
    } else if (maxWeek < DEFAULT_TOTAL_WEEKS) {
        warn('学期总周数教务没有提供，已按 ' + totalWeeks + ' 周计（课表里最大的周次是 ' + maxWeek + ' 周），如校历不同请在学期管理里调整');
    }

    warn('节次时间用的是适配器内置的徐州医科大学作息表（' + PERIOD_TIMES.length + ' 节），如与教务不一致请在节次设置里调整');
    if (untimed.length) {
        warn('第 ' + untimed.join('、') + ' 节的上下课时间内置作息表里没有，课表里这几节不会显示上下课时间');
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termName,
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                periodTimes: PERIOD_TIMES,
                courses: courses
            }
        ]
    });
})()
