(function () {
    // 湖南师范大学教务适配器（树维 EAMS 平台，路径 /eams/；上海树维信息科技有限公司
    // SupWisdom，新开普子公司 —— 不是强智）—— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 HUNNU/hunnu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 EarOfWheat）
    //   上游快照 commit e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 输入是 extract.js 交出来的**课表页 HTML 字符串**（不是结构化 JSON，这是本件与同族走接口的
    // 适配器最大的差别）：课程数据内嵌在页面里，形如
    //     var actTeachers = [{id: 0, name: "张三"}];
    //     var activity = new TaskActivity(..., "课名(类别)", null, "教室", "周次位图");
    //     activity.id = ...; index = 4 * unitCount + 2; table0.marshalTable(...);
    //
    // 移植改动（逐条）：
    //   ① 上游「取 HTML + 解析课程 + 算周次 + 合并节次」在一个自执行脚本里；这里只做转换，
    //      所以 CI 能用 Rhino 真跑一遍（extract.js 需要浏览器，永远进不了 CI）。
    //   ② 上游分两块解析：先用一个正则抓 (教师名, 参数串, 尾部)，再用第二个正则扫尾部里的
    //      index 赋值。抓教师名的那个正则要求同一块里 name 是双引号、且紧跟右花括号，遇到
    //      name 后面还有别的字段、或教师数组里有多个老师时会匹配不到，整块连同课一起丢掉
    //      （没有 warnings）。这里改成：按 var actTeachers = [...] 到 new TaskActivity(...)
    //      的结构切块（不依赖 name 的写法和数组元素个数），块内再单独读教师名。
    //   ③ 上游用 args[1]（或 args[1].join(",")）当教师，实测同一个平台的脚本里 args[1]
    //      写成 join 表达式的情况就有；本件按上游 HUNNU 的读法取同一块里的 actTeachers 名字，
    //      读不到就留空（null）——不写「未知教师」，那会被当成真姓名显示。
    //   ④ 周次位图：与同族 5 件的约定一致，下标 i 就是第 i 周，下标 0 是占位符。
    //      上游本来就是 for (i = 1; ...)，但那样会把第 0 位的 1 静默吞掉；这里显式检查
    //      第 0 位并写一条 warnings（不静默丢数据）。
    //   ⑤ 上游的 index = 星期 * unitCount + 节次 正则要求乘数处是字面量数字；本件同时认
    //      index = 5 * unitCount + 2（变量）与 index = 62（已经算好的裸数字，按 unitCount
    //      反推），并且不对表达式做任何动态求值（同族上游有脚本把页面里的表达式当代码跑，
    //      命中移植手册 §5 第 6 条）。
    //   ⑥ 上游在同一门课的相邻节次之间做合并（把第 1、2 节拼成 1-2 节），合并会把两节不同的
    //      周次集合起来，丢信息。这里不做合并：教务页面本来就是按节次一个一个给的，
    //      载荷里保持「一个节次一条 block」，应用自己会画成连堂的样子。
    //   ⑦ 上游硬编码 UNIT_COUNT = 13；这里优先读课表 HTML 里的 unitCount，读不到才用 13
    //      并写 warnings，两者不一致时也写 warnings。
    //   ⑧ 作息时间用上游内置的 13 节表（湖南师范大学教务处作息），AUDIT.md 里写了来源。
    //   ⑨ 新增 warnings：推算的开学日与学期名、内置作息表、认不出来的块 / 参数 / index 赋值、
    //      空周次、周次截断、节次越界。
    var data = JSON.parse(__ncInput);
    var html = text(data.html);

    var DEFAULT_UNIT_COUNT = 13;
    var MAX_UNIT_COUNT = 30;
    var DEFAULT_TOTAL_WEEKS = 20;
    var MAX_TOTAL_WEEKS = 30;
    var MAX_WARNINGS = 20;
    var TERM_NAME_PREFIX = '湖南师范大学 ';

    // TaskActivity 的参数位（本平台一致）：args[3] = 课名，args[5] = 教室，args[6] = 周次位图
    var ARGS_NAME = 3;
    var ARGS_LOCATION = 5;
    var ARGS_WEEKS = 6;

    var BACKSLASH = String.fromCharCode(92);
    var QUOTE = String.fromCharCode(34);
    var SINGLE = String.fromCharCode(39);

    // 湖南师范大学作息时间（13 节），出自上游 HUNNU/hunnu.js 内置的 TIME_SLOTS 表。
    // 上游在导入时把它当「预设作息」写给应用；这里作为载荷的 periodTimes。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '12:45', end: '13:30' },
        { periodIndex: 6, start: '13:30', end: '14:15' },
        { periodIndex: 7, start: '14:30', end: '15:15' },
        { periodIndex: 8, start: '15:25', end: '16:10' },
        { periodIndex: 9, start: '16:30', end: '17:15' },
        { periodIndex: 10, start: '17:25', end: '18:10' },
        { periodIndex: 11, start: '19:00', end: '19:45' },
        { periodIndex: 12, start: '19:55', end: '20:40' },
        { periodIndex: 13, start: '20:50', end: '21:35' }
    ];

    // 正则一律用 new RegExp 的字符串形态写：源码里少一处反斜杠，就少一处被编辑工具
    // 落成真控制字符的机会（写完自检过 NUL 为 0）。
    var SPACE_RE = new RegExp('\\s+', 'g');
    var SPACE_CHAR_RE = new RegExp('\\s');
    var UNIT_COUNT_RE = new RegExp('unitCount\\s*=\\s*(\\d{1,3})');
    var BLOCK_RE = new RegExp(
        'var\\s+actTeachers\\s*=\\s*(\\[[\\s\\S]*?\\])\\s*;' +
        '[\\s\\S]*?activity\\s*=\\s*new\\s+TaskActivity\\s*\\(([\\s\\S]*?)\\)\\s*;' +
        '([\\s\\S]*?)(?=var\\s+actTeachers|table0\\.marshalTable|$)',
        'g'
    );
    var SEMESTER_NAME_RE = new RegExp(
        '20\\d{2}\\s*[-—~至]\\s*20\\d{2}\\s*学年\\s*第?\\s*[0-9一二三四五六七八九]{1,2}\\s*学期'
    );
    var SEMESTER_LOOSE_RE = new RegExp('20\\d{2}\\s*[-—~]\\s*20\\d{2}\\s*学年');

    var warnings = [];

    function warn(message) {
        if (warnings.length >= MAX_WARNINGS) return;
        if (warnings.indexOf(message) >= 0) return;
        warnings.push(message);
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    // 删掉字符串里的控制字符（页面文本里可能出现），名字里不该有它们。
    // 区间端点用 String.fromCharCode 拼出来，避免在源码里出现真的控制字符。
    var CTRL_RE = new RegExp(
        '[' + String.fromCharCode(0) + '-' + String.fromCharCode(31) + String.fromCharCode(127) + ']',
        'g'
    );

    function tidy(value) {
        return text(value).replace(SPACE_RE, ' ').replace(CTRL_RE, '').trim();
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

    function isDigit(ch) {
        return ch >= '0' && ch <= '9';
    }

    function isLetter(ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch === '_' || ch === '$';
    }

    function skipSpaces(source, at) {
        var i = at;
        while (i < source.length && SPACE_CHAR_RE.test(source.charAt(i))) i++;
        return i;
    }

    // JS 字符串字面量里的转义序列 → 真字符（转义的双引号 → 双引号、转义的反斜杠 → 反斜杠、
    // 十六进制转义 → 那个字）。名字里出现换行转义时按空格算（课名是一行文本）。
    // 未知转义按 JS 的语义去掉反斜杠。
    function unescapeBody(body) {
        var out = '';
        var i = 0;
        while (i < body.length) {
            var ch = body.charAt(i);
            if (ch !== BACKSLASH) {
                out += ch;
                i++;
                continue;
            }
            var next = body.charAt(i + 1);
            if (next === '') {
                out += BACKSLASH;
                i++;
                continue;
            }
            if (next === BACKSLASH) { out += BACKSLASH; i += 2; continue; }
            if (next === QUOTE) { out += QUOTE; i += 2; continue; }
            if (next === SINGLE) { out += SINGLE; i += 2; continue; }
            if (next === 'n' || next === 't' || next === 'r' || next === 'f' || next === 'v') {
                out += ' ';
                i += 2;
                continue;
            }
            if (next === 'u') {
                var code = parseInt(body.substr(i + 2, 4), 16);
                if (!isNaN(code)) { out += String.fromCharCode(code); i += 6; continue; }
            }
            if (next === 'x') {
                var small = parseInt(body.substr(i + 2, 2), 16);
                if (!isNaN(small)) { out += String.fromCharCode(small); i += 4; continue; }
            }
            out += next;
            i += 2;
        }
        return out;
    }

    // 从 at 处的引号开始读一个 JS 字符串字面量，返回 {value, end}（end = 收尾引号之后）。
    // 认反斜杠转义，所以教师名里写成转义双引号时不会被当成字符串结束。
    function readQuoted(source, at) {
        var quote = source.charAt(at);
        if (quote !== QUOTE && quote !== SINGLE) return null;
        var body = '';
        var i = at + 1;
        while (i < source.length) {
            var ch = source.charAt(i);
            if (ch === BACKSLASH) {
                body += ch + source.charAt(i + 1);
                i += 2;
                continue;
            }
            if (ch === quote) return { value: unescapeBody(body), end: i + 1 };
            body += ch;
            i++;
        }
        return null;
    }

    // 从 actTeachers 数组文本里读第一个教师名（name 后面单引号或双引号都认）。
    // 读不到返回空串 —— 调用方把它留空，不写「未知教师」。
    function teacherFromArray(arrayText) {
        var source = text(arrayText);
        var at = source.indexOf('name');
        while (at >= 0) {
            var colon = source.indexOf(':', at + 4);
            if (colon < 0) return '';
            var cursor = skipSpaces(source, colon + 1);
            var quoted = readQuoted(source, cursor);
            if (quoted) return tidy(quoted.value);
            at = source.indexOf('name', at + 4);
        }
        return '';
    }

    // 按逗号切 TaskActivity 的参数，引号内和括号内的逗号不算（上游 splitArgs 的逐字重写）。
    function splitArgs(source) {
        var result = [];
        var current = '';
        var depth = 0;
        var inQuote = false;
        var quote = '';
        for (var i = 0; i < source.length; i++) {
            var ch = source.charAt(i);
            if (inQuote) {
                current += ch;
                if (ch === BACKSLASH) {
                    current += source.charAt(i + 1);
                    i++;
                    continue;
                }
                if (ch === quote) inQuote = false;
                continue;
            }
            if (ch === QUOTE || ch === SINGLE) {
                inQuote = true;
                quote = ch;
                current += ch;
                continue;
            }
            if (ch === '(' || ch === '[' || ch === '{') { depth++; current += ch; continue; }
            if (ch === ')' || ch === ']' || ch === '}') { if (depth > 0) depth--; current += ch; continue; }
            if (ch === ',' && depth === 0) {
                result.push(tidy(current));
                current = '';
                continue;
            }
            current += ch;
        }
        if (tidy(current)) result.push(tidy(current));
        return result;
    }

    // 一个参数 → 它的字符串值。字面量去引号（认转义）；表达式（数组 join 那一类）里的字面量
    // 拼起来；读不出任何字面量就返回空串（调用方留空，不写「未知」）。
    function stringOfArg(arg) {
        var source = text(arg);
        if (!source) return '';
        var first = source.charAt(0);
        if (first === QUOTE || first === SINGLE) {
            var quoted = readQuoted(source, 0);
            if (quoted) return tidy(quoted.value);
            return '';
        }
        var parts = [];
        var i = 0;
        while (i < source.length) {
            var ch = source.charAt(i);
            if (ch === QUOTE || ch === SINGLE) {
                var piece = readQuoted(source, i);
                if (!piece) break;
                parts.push(piece.value);
                i = piece.end;
                continue;
            }
            i++;
        }
        return tidy(parts.join(''));
    }

    // 周次位图 → 周次数组。**下标 i 就是第 i 周，下标 0 是占位符**（本批统一口径）。
    function weeksFromBitmap(bitmap) {
        var weeks = [];
        var source = text(bitmap);
        for (var i = 1; i < source.length; i++) {
            if (source.charAt(i) === '1') weeks.push(i);
        }
        if (source.length > 0 && source.charAt(0) === '1') {
            warn('周次位图第 0 位为 1，与同族约定的占位符（第 0 位固定为 0）不符，已按忽略处理、' +
                '不产生「第 0 周」，请在导入预览里核对周次');
        }
        return weeks;
    }

    function uniqueSorted(weeks) {
        var seen = {};
        var out = [];
        for (var i = 0; i < weeks.length; i++) {
            var w = weeks[i];
            if (w >= 1 && !seen[w]) {
                seen[w] = true;
                out.push(w);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // 周次集合 → 极大段：连续（步长 1）→ ALL，隔周（步长 2）→ ODD / EVEN，落单的一周 → ALL
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

    // 读一个因子：字面量数字给值，标识符给 null（值未知但不报错）。
    function readFactor(source, at) {
        if (isDigit(source.charAt(at))) {
            var i = at;
            var digits = '';
            while (i < source.length && isDigit(source.charAt(i))) {
                digits += source.charAt(i);
                i++;
            }
            return { value: parseInt(digits, 10), end: i };
        }
        if (isLetter(source.charAt(at))) {
            var j = at;
            while (j < source.length) {
                var ch = source.charAt(j);
                if (!(isLetter(ch) || isDigit(ch) || ch === '.')) break;
                j++;
            }
            return { value: null, end: j };
        }
        return null;
    }

    // 尾部（TaskActivity 之后到下一个块之前）里的 index 赋值。
    // 认两种写法：index = 5 * unitCount + 2（乘数可变量、可裸数字）与 index = 62（已算好）。
    // 不对表达式做任何动态求值 —— 表达式来自页面里的文本，手册 §5 第 6 条不允许。
    function positionsFromTail(tail, unitCount) {
        var source = text(tail);
        var out = [];
        var at = 0;
        while (at < source.length) {
            var found = source.indexOf('index', at);
            if (found < 0) break;
            at = found + 5;
            var cursor = skipSpaces(source, at);
            if (source.charAt(cursor) !== '=' || source.charAt(cursor + 1) === '=') continue;
            cursor = skipSpaces(source, cursor + 1);
            var left = readFactor(source, cursor);
            if (!left) continue;
            cursor = skipSpaces(source, left.end);
            var ch = source.charAt(cursor);
            if (ch === ';') {
                if (left.value === null) continue;
                // 已经算好的一维下标：index = 星期 * unitCount + 节次（0 基）
                out.push({
                    day: Math.floor(left.value / unitCount) + 1,
                    period: (left.value % unitCount) + 1,
                    computed: true
                });
                continue;
            }
            if (ch !== '*') continue;
            cursor = skipSpaces(source, cursor + 1);
            var middle = readFactor(source, cursor);
            if (!middle) continue;
            cursor = skipSpaces(source, middle.end);
            if (source.charAt(cursor) !== '+') continue;
            cursor = skipSpaces(source, cursor + 1);
            var last = readFactor(source, cursor);
            if (!last) continue;
            cursor = skipSpaces(source, last.end);
            if (source.charAt(cursor) !== ';') continue;
            if (left.value === null || last.value === null) {
                out.push({ day: 0, period: 0, unreadable: true });
                continue;
            }
            out.push({ day: left.value + 1, period: last.value + 1 });
        }
        return out;
    }

    function academicTermName(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        if (month >= 9) return year + '-' + (year + 1) + '学年第一学期';
        if (month === 1) return (year - 1) + '-' + year + '学年第一学期';
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
        for (i = 0; i < candidates.length; i++) {
            var loose = SEMESTER_LOOSE_RE.exec(tidy(candidates[i]));
            if (loose) return tidy(loose[0]);
        }
        return '';
    }

    // ---- 读 unitCount：课表 HTML 里的那份最贴近数据，其次才用页面自己读到的 ----
    var htmlUnitCount = null;
    var htmlUnitMatch = UNIT_COUNT_RE.exec(html);
    if (htmlUnitMatch) {
        var parsedUnit = parseInt(htmlUnitMatch[1], 10);
        if (parsedUnit >= 1 && parsedUnit <= MAX_UNIT_COUNT) htmlUnitCount = parsedUnit;
    }
    var pageUnitCount = intOf(data.unitCount, 0);
    if (!(pageUnitCount >= 1 && pageUnitCount <= MAX_UNIT_COUNT)) pageUnitCount = 0;
    var unitCount = htmlUnitCount || pageUnitCount || 0;
    var unitCountKnown = unitCount > 0;
    if (!unitCountKnown) {
        unitCount = DEFAULT_UNIT_COUNT;
        warn('节次数没能从页面里读到（页面里没有 unitCount），已按内置的 13 节换算星期与节次，请核对课表');
    } else if (htmlUnitCount && pageUnitCount && htmlUnitCount !== pageUnitCount) {
        warn('课表页里的节次数（' + htmlUnitCount + '）与页面上的 unitCount（' + pageUnitCount +
            '）不一致，已按课表页里的 ' + htmlUnitCount + ' 节换算，请核对课表');
    }

    // ---- 切块：var actTeachers = [...]; ... activity = new TaskActivity(...); 尾部 ----
    var parsed = { courses: [], totalBlocks: 0, wrongArgCount: 0, unreadableIndex: 0, computedIndex: 0, emptyWeeks: 0, periodOutOfRange: 0 };
    var courseOrder = [];
    var byName = {};
    var blockSeen = {};

    function courseOf(name, teacher) {
        var nameKey = 'k' + name;
        if (!Object.prototype.hasOwnProperty.call(byName, nameKey)) byName[nameKey] = {};
        var perTeacher = byName[nameKey];
        var teacherKey = 't' + teacher;
        if (!Object.prototype.hasOwnProperty.call(perTeacher, teacherKey)) {
            var course = { name: name, teacher: teacher ? teacher : null, note: null, blocks: [] };
            perTeacher[teacherKey] = course;
            courseOrder.push(course);
            blockSeen[nameKey] = {};
        }
        return perTeacher[teacherKey];
    }

    function addBlock(course, block) {
        var nameKey = 'k' + course.name;
        if (!Object.prototype.hasOwnProperty.call(blockSeen, nameKey)) blockSeen[nameKey] = {};
        var key = JSON.stringify(block);
        if (blockSeen[nameKey][key]) return;
        blockSeen[nameKey][key] = true;
        course.blocks.push(block);
    }

    var match;
    BLOCK_RE.lastIndex = 0;
    while ((match = BLOCK_RE.exec(html)) !== null) {
        if (match[0] === '') {
            BLOCK_RE.lastIndex++;
            continue;
        }
        var teacher = teacherFromArray(match[1]);
        var argsSource = match[2];
        var tail = match[3];
        var args = splitArgs(argsSource);
        parsed.totalBlocks++;
        if (args.length <= ARGS_WEEKS) {
            parsed.wrongArgCount++;
            continue;
        }
        var courseFull = stringOfArg(args[ARGS_NAME]);
        if (!courseFull) continue;
        // 上游把课名截到第一个半角括号之前（「高等数学A(一)」→「高等数学A」）；课名以括号
        // 开头时上游那条正则会切出更怪的结果，这里直接按整串算。
        var nameMatch = /^[^(]+/.exec(courseFull);
        var name = tidy(nameMatch ? nameMatch[0] : courseFull);
        var location = stringOfArg(args[ARGS_LOCATION]);
        var bitmap = stringOfArg(args[ARGS_WEEKS]);
        if (!name) continue;

        var weeks = uniqueSorted(weeksFromBitmap(bitmap));
        if (!weeks.length) {
            parsed.emptyWeeks++;
            continue;
        }
        var positions = positionsFromTail(tail, unitCount);
        if (!positions.length) continue;

        var course = null;
        for (var p = 0; p < positions.length; p++) {
            var spot = positions[p];
            if (spot.unreadable) {
                parsed.unreadableIndex++;
                continue;
            }
            if (spot.computed) parsed.computedIndex++;
            if (!(spot.day >= 1 && spot.day <= 7)) {
                parsed.unreadableIndex++;
                continue;
            }
            if (!(spot.period >= 1 && spot.period <= unitCount)) {
                parsed.unreadableIndex++;
                continue;
            }
            if (spot.period > PERIOD_TIMES.length) {
                parsed.periodOutOfRange++;
                continue;
            }
            if (!course) course = courseOf(name, teacher);
            var runs = runsOf(weeks);
            for (var r = 0; r < runs.length; r++) {
                addBlock(course, {
                    dayOfWeek: spot.day,
                    startPeriod: spot.period,
                    endPeriod: spot.period,
                    startWeek: runs[r].start,
                    endWeek: runs[r].end,
                    weekType: runs[r].weekType,
                    location: location ? location : null
                });
            }
        }
    }

    // 重复的排课：教务把同一门课的同一个排课在页面里画两遍时（本批上游的合并逻辑也被这段
    // 重复数据喂过），这里按 (星期, 节次, 周次段, 教室) 去重，只留一条。
    var duplicateBlocks = 0;
    for (var d = 0; d < courseOrder.length; d++) {
        var unique = [];
        var seenBlock = {};
        var courseBlocks = courseOrder[d].blocks;
        for (var u = 0; u < courseBlocks.length; u++) {
            var blockKey = JSON.stringify(courseBlocks[u]);
            if (seenBlock[blockKey]) {
                duplicateBlocks++;
                continue;
            }
            seenBlock[blockKey] = true;
            unique.push(courseBlocks[u]);
        }
        courseOrder[d].blocks = unique;
    }

    for (c = 0; c < courseOrder.length; c++) {
        if (courseOrder[c].blocks.length) parsed.courses.push(courseOrder[c]);
    }

    if (!parsed.courses.length) {
        throw new Error('课表页里没有解析到任何课程。请确认「我的课表」已经显示出来（页面上能看到课表），再点「提取课表」');
    }

    // ---- 周次上限、期末总周数 ----
    var c;
    var maxWeek = 0;
    var clampedWeeks = false;
    for (c = 0; c < parsed.courses.length; c++) {
        var blocks = parsed.courses[c].blocks;
        blocks.sort(function (a, b) {
            return (a.dayOfWeek - b.dayOfWeek) || (a.startPeriod - b.startPeriod) ||
                (a.startWeek - b.startWeek) || (a.endWeek - b.endWeek) ||
                (a.location === b.location ? 0 : (a.location ? 1 : -1));
        });
        for (var b = 0; b < blocks.length; b++) {
            if (blocks[b].endWeek > maxWeek) maxWeek = blocks[b].endWeek;
        }
    }
    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_TOTAL_WEEKS) totalWeeks = MAX_TOTAL_WEEKS;
    if (maxWeek > totalWeeks) {
        clampedWeeks = true;
        for (c = 0; c < parsed.courses.length; c++) {
            var list = parsed.courses[c].blocks;
            for (var k = 0; k < list.length; k++) {
                if (list[k].endWeek > totalWeeks) list[k].endWeek = totalWeeks;
                if (list[k].startWeek > totalWeeks) list[k].startWeek = totalWeeks;
            }
        }
        maxWeek = totalWeeks;
    }

    // ---- 节次时间：内置 13 节作息表，只发课表里用得到的那几节 ----
    // 节次越界（大于内置作息表的节数）的 block 已经在切块那一步丢掉了，这里只算范围。
    var maxPeriod = 0;
    for (c = 0; c < parsed.courses.length; c++) {
        for (var m = 0; m < parsed.courses[c].blocks.length; m++) {
            if (parsed.courses[c].blocks[m].endPeriod > maxPeriod) maxPeriod = parsed.courses[c].blocks[m].endPeriod;
        }
    }
    var periodCount = maxPeriod > PERIOD_TIMES.length ? PERIOD_TIMES.length : maxPeriod;
    if (!(periodCount >= 1)) periodCount = 0;
    var periodTimes = [];
    for (var pt = 0; pt < periodCount; pt++) periodTimes.push(PERIOD_TIMES[pt]);
    if (periodCount > 0) {
        warn('节次时间用的是适配器内置的湖南师范大学作息表（' + PERIOD_TIMES.length +
            ' 节），如与教务处公布的作息不一致请在节次设置里调整');
    }

    // ---- 开学日：本适配器零请求，拿不到校历，只能按最近的周一推算 ----
    var now = parseIso(data.today) || new Date();
    var currentWeek = intOf(data.currentWeek, 0);
    var firstDay;
    if (currentWeek >= 1 && currentWeek <= MAX_TOTAL_WEEKS) {
        var thisMonday = mondayIso(now);
        firstDay = isoOf(shiftDays(parseIso(thisMonday), -(currentWeek - 1) * 7));
        warn('开学日期教务没有提供，已按页面上显示的「第 ' + currentWeek + ' 周」反推为 ' +
            firstDay + '，请在学期管理里核对');
    } else {
        firstDay = mondayIso(now);
        warn('开学日期教务没有提供（本适配器不请求校历接口），已按最近的周一（' + firstDay +
            '）推算，请在学期管理里核对');
    }

    var termName = termNameFromPage(data.title, data.semesterLabels || []);
    if (!termName) {
        termName = TERM_NAME_PREFIX + academicTermName(now);
        warn('学期名教务没有提供，已按导入日期推算为「' + termName + '」，如与实际不符可在学期管理里改名');
    }

    if (maxWeek < DEFAULT_TOTAL_WEEKS) {
        if (clampedWeeks) {
            warn('学期总周数教务没有提供，课表里的周次超过 ' + MAX_TOTAL_WEEKS +
                ' 周、载荷上限就是 ' + MAX_TOTAL_WEEKS + ' 周，已按 ' + totalWeeks +
                ' 周计并截断周次，请核对课表');
        } else {
            warn('学期总周数教务没有提供，已按 ' + totalWeeks + ' 周计（课表里最大的周次是 ' + maxWeek +
                ' 周），如校历不同请在学期管理里调整');
        }
    }
    if (clampedWeeks && maxWeek >= DEFAULT_TOTAL_WEEKS) {
        warn('课表里出现了超过 ' + MAX_TOTAL_WEEKS + ' 周的周次，已按 ' + totalWeeks + ' 周截断，请核对课表');
    }
    if (duplicateBlocks) {
        warn('课表里有 ' + duplicateBlocks + ' 条重复的排课（教务把同一门课在页面里画了两遍），已去重，请核对课表');
    }
    if (parsed.periodOutOfRange) {
        warn('有 ' + parsed.periodOutOfRange + ' 处安排的节次超过了内置作息表的 ' + PERIOD_TIMES.length +
            ' 节，已跳过（不静默改节次），请核对课表');
    }
    if (!parsed.courses.length && parsed.totalBlocks > 0) {
        warn('页面里有课程块但一门都没能解析出来，请把这一页反馈给适配器维护者');
    }
    if (parsed.totalBlocks === 0) {
        warn('页面 HTML 里没有找到 TaskActivity 课程数据块，可能是课表还没加载完或教务改了页面结构');
    }
    if (parsed.wrongArgCount) {
        warn('有 ' + parsed.wrongArgCount + ' 个课程块的参数个数与预期不符（少于 7 个），已跳过，请核对课表');
    }
    if (parsed.emptyWeeks) {
        warn('有 ' + parsed.emptyWeeks + ' 个课程块的周次位图是空的（或全是 0），已跳过，请核对课表');
    }
    if (parsed.unreadableIndex) {
        warn('有 ' + parsed.unreadableIndex + ' 处 index 赋值没能解析出星期与节次（写法与预期不符），已跳过，请核对课表');
    }
    if (parsed.computedIndex) {
        warn('有 ' + parsed.computedIndex + ' 处 index 是已经算好的数字，已按课表页里的 unitCount（' +
            unitCount + ' 节）反推星期与节次，请核对课表');
    }
    if (data.source === 'iframe' && !data.frameFound) {
        warn('页面上没有找到「我的课表」的 iframe，读到的是外层页面的 HTML，可能解析不到课程');
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
                periodTimes: periodTimes,
                courses: parsed.courses
            }
        ]
    });
})()
