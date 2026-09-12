(function () {
    // 重庆财经学院教务适配器（正方新版 jwglxt 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 CFEC/CFEC.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 glxgo）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游 CFEC.js 与 ZJUT/zjut_01.js 是逐字近克隆（整段 diff 只有菜单号 N2151 / 作息表 /
    // 默认校区号三处），所以本文件以已移植的 jw-adapters/zjut/parse.js 为模板；
    // 但同平台≠同编码，两段脚本都按 CFEC 自己的数据单独造了 fixture（见 docs/jw-adapter-testing.md §3.2）。
    //
    // 上游在同一段脚本里算周次、拼课程、存配置；这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑），
    // 取数全部在 extract.js 里，交出来的就是教务的行对象。
    //
    // 移植改动（按移植手册 §4 与第一批审查挖出来的坑逐条对照）：
    //   ① 周次解析加固：单/双写在「周」之后（"1-16周(双)"）或自己成一段（"1-16周,双"）都不丢，
    //      认不出的单/双标记计数写进 warnings；括号里的纯数字（"(1)"）是序号不是周次，先摘掉
    //   ② 上游读不出课程名 / 星期 / 节次 / 周次的行直接 return（静默丢课）：这里逐类计数写进 warnings
    //   ③ 节次写法放宽到 "1-2" / "第9-10节" / "1,2" / 两位一拼的 "0102"，多段（"1-2,5-6"）按最大跨度
    //      放入并写进 warnings（不静默丢，也不静默改）
    //   ④ 开学日与总周数：优先取教务的学期周次校历（extract.js 另取），拿不到才推算并如实说明（手册 §4.2）；
    //      总周数被课表里更晚的周次抬高时单独说明（手册：其它推算值都写了，唯独这个最容易漏）
    //   ⑤ 作息时间：优先取教务的校区作息，拿不到回落到上游那张 13 节表；课表用到的节次超出作息表时
    //      按空课内建节次表补足，两种情况都写进 warnings（连堂/多节必须每节都有时间）
    //   ⑥ 教务另外给出的集中实践课（sjkList：只有课名与起止周、没有星期节次）排不进周课表，
    //      但也不静默丢，逐门写进 warnings
    //   ⑦ 教师 / 教室没有就留空：上游写的「未知」「未排地点」会被课表当成真名显示
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = Array.isArray(raw.kbList) ? raw.kbList : [];
    var practiceRows = Array.isArray(raw.sjkList) ? raw.sjkList : [];

    // 复合键（课名 + 教师）的分隔符取 NUL。用 String.fromCharCode 取，源码里不出现控制字符、
    // 也不出现转义序列 —— 第一批有两个适配器把转义序列落成了真的 NUL 字节，文件被 grep 当二进制看。
    var SEP = String.fromCharCode(0);

    var MAX_WEEK = 30;            // 载荷校验：totalWeeks ∈ 1..30
    var MAX_PERIOD = 20;          // 单日节次上限：超过它一定是脏数据（这所学校作息只有 13 节）
    var FALLBACK_TOTAL_WEEKS = 20; // 校历与课表都给不出总周数时的兜底

    // 学校作息（重庆财经学院）：取自上游 CFEC/CFEC.js 里那张预设节次表，原样搬过来。
    // 只在教务的作息接口取不到可用数据时使用，用了就写进 warnings。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:10', end: '08:50' },
        { periodIndex: 2, start: '09:00', end: '09:40' },
        { periodIndex: 3, start: '09:50', end: '10:30' },
        { periodIndex: 4, start: '10:40', end: '11:20' },
        { periodIndex: 5, start: '11:30', end: '12:10' },
        { periodIndex: 6, start: '14:10', end: '14:50' },
        { periodIndex: 7, start: '15:00', end: '15:40' },
        { periodIndex: 8, start: '15:50', end: '16:30' },
        { periodIndex: 9, start: '16:40', end: '17:20' },
        { periodIndex: 10, start: '18:30', end: '19:10' },
        { periodIndex: 11, start: '19:20', end: '20:00' },
        { periodIndex: 12, start: '20:10', end: '20:50' },
        { periodIndex: 13, start: '21:00', end: '21:40' }
    ];

    // 空课内置节次表（:core:model 的 DefaultPeriodTimes，12 节）：课表用到的节次超出学校作息表时
    // 用它把缺的节次补出来（载荷带了 periodTimes 时应用不会自己补），并写进 warnings。
    var BUILTIN_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '16:00', end: '16:45' },
        { periodIndex: 8, start: '16:55', end: '17:40' },
        { periodIndex: 9, start: '18:30', end: '19:15' },
        { periodIndex: 10, start: '19:25', end: '20:10' },
        { periodIndex: 11, start: '20:20', end: '21:05' },
        { periodIndex: 12, start: '21:15', end: '22:00' }
    ];

    var KIND_CN = { first: '一', second: '二', third: '三' };
    // 推算开学日的锚点：中国高校第一学期多在 9 月初、第二学期多在 2 月下旬、夏季学期 7 月初。
    // 别直接把锚点当 firstDay：手册 §4.3 要求回退到「第 1 周的第一天」那个周一（含当天）。
    var KIND_ANCHOR = {
        first: { month: 9, day: 1, rule: '第一学期 = 9 月 1 日所在周的周一' },
        second: { month: 2, day: 20, rule: '第二学期 = 2 月 20 日所在周的周一' },
        third: { month: 7, day: 1, rule: '夏季学期 = 7 月 1 日所在周的周一' }
    };

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value) {
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    // 该日期所在周的周一（用 UTC 算，避免时区把日期挪一天）
    function mondayOnOrBefore(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() + 6) % 7;
        return new Date(date.getTime() - offset * 86400000);
    }

    // 任意形态的日期 → ISO 日期（只认年月日，后面的时间/星期部分忽略）
    function isoOfAny(value) {
        var m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(text(value));
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    function mondayOfIso(iso) {
        if (!iso) return null;
        return isoOf(mondayOnOrBefore(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10),
            parseInt(iso.substring(8, 10), 10)
        ));
    }

    function localTodayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    // "08:10" / "08:10:00" → "08:10"；认不出来返回 null
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // 教务接口的返回可能是裸数组，也可能套一层信封（{rows:[]} / {code:0,datas:{...}}）。
    // extract.js 原样交出来，这里按「最像行数组的那一层」找：先看裸数组，再按常见键名逐层找。
    // 找不到就返回空数组 —— 调用方据此回落到推算，并如实写进 warnings。
    function rowsIn(value) {
        if (Array.isArray(value)) return value;
        if (!value || typeof value !== 'object') return [];
        var keys = ['rows', 'datas', 'data', 'list', 'items', 'kbList'];
        var i;
        for (i = 0; i < keys.length; i++) {
            var known = rowsIn(value[keys[i]]);
            if (known.length) return known;
        }
        for (var key in value) {
            if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
            var found = rowsIn(value[key]);
            if (found.length) return found;
        }
        return [];
    }

    // ---------- 学期名与学期序号 ----------
    // 优先看教务下拉框的文本（「一/二/三」），认不出来再看正方自己的学期代号
    // （正方 jwglxt：3 = 第一学期、12 = 第二学期、16 = 第三学期）
    function termKind() {
        var label = text(term.xqmText);
        if (label.indexOf('二') >= 0) return 'second';
        if (label.indexOf('三') >= 0) return 'third';
        if (label.indexOf('一') >= 0) return 'first';
        var code = text(term.xqm);
        if (code === '12' || code === '2') return 'second';
        if (code === '16') return 'third';
        return 'first';
    }

    function termYear() {
        return /^[0-9]{4}$/.test(text(term.xnm)) ? parseInt(text(term.xnm), 10) : 0;
    }

    function yearLabel() {
        var label = text(term.xnmText);
        if (/^[0-9]{4}-[0-9]{2,4}$/.test(label)) return label;
        var year = termYear();
        if (year) return year + '-' + (year + 1);
        return label || text(term.xnm);
    }

    // 学期名用教务自己的学年学期（手册 §4.7：别拿适配器名当学期名）
    function termName() {
        var explicit = text(term.name);
        if (explicit) return explicit;
        var whole = text(term.xqmText);
        // 有的学校把整个「2026-2027学年第一学期」放进学期下拉框
        if (whole && whole.indexOf('学年') >= 0 && whole.indexOf('学期') >= 0) return whole;
        return yearLabel() + '学年第' + KIND_CN[termKind()] + '学期';
    }

    // 教务校历取不到时的开学日推算。依赖学期序号的锚点，推算结果一律写进 warnings。
    function estimateStart(todayIso) {
        var anchor = KIND_ANCHOR[termKind()];
        var year = termYear();
        var kind = termKind();
        if (!year) {
            // 学年读不出来（课表页被改过）时退回「今天的周一」。这条分支依赖当天日期，
            // 不要写进 fixture 用例 —— 用例会随日期失效
            return { iso: mondayOfIso(todayIso) || todayIso, rule: '今天的周一' };
        }
        return {
            iso: isoOf(mondayOnOrBefore(year + (kind === 'first' ? 0 : 1), anchor.month, anchor.day)),
            rule: anchor.rule
        };
    }

    // 校历万一给的是别的学期（例如接口忽略了 xnm / xqm），宁可按推算走 + 说出来，
    // 也不要悄悄写一个错日期：开学日错了整学期的课都会错位。
    function plausibleStart(iso) {
        var year = termYear();
        if (!year) return true;
        var kind = termKind();
        var anchor = KIND_ANCHOR[kind];
        var anchorYear = kind === 'first' ? year : year + 1;
        var target = Date.UTC(anchorYear, anchor.month - 1, anchor.day);
        var value = Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        );
        if (isNaN(target) || isNaN(value)) return true;
        return Math.abs(value - target) <= 45 * 86400000;
    }

    // 周次文本 → 周次集合（去重、升序）。正方的写法：
    //   "1-16周" / "1-16周(单)" / "2-16周(双)" / "1-4,6-8周" / "1-4周，6-8周" / "17周"
    // 三个坑（第一批实测出来的，克隆会把它们一起克隆过来）：
    //   ① 「周」是量词：留着它，"1-16周(双)" 里紧跟其后的 "(双)" 会被切成另一段，单/双标记整段丢掉，
    //      这门课就退化成「每周都上」而且一声不吭 —— 所以先把「周」整个删掉再切段；
    //   ② 单/双标记也可能自己成一段（"1-16周,双"）：并给最近的周次段，不丢；
    //   ③ 括号里的纯数字是序号（"(1)"）不是周次，先摘掉，免得它被当成第 1 周。
    var droppedWeeks = 0;   // 超出 1..MAX_WEEK 被丢掉的周次个数
    var orphanMarkers = 0;  // 带单/双标记但找不到任何周次段的段数

    function weeksOf(source) {
        var cleaned = text(source);
        if (!cleaned) return [];
        cleaned = cleaned.replace(/周/g, '')
            .replace(/[(（]\s*[0-9]+\s*[)）]/g, '')
            .replace(/[(（]/g, '')
            .replace(/[)）]/g, '');
        var tokens = cleaned.split(/[,，、;；\s]+/);
        var segments = [];
        var i;
        for (i = 0; i < tokens.length; i++) {
            if (tokens[i]) segments.push(tokens[i]);
        }
        for (i = 0; i < segments.length; i++) {
            if (/[0-9]/.test(segments[i])) continue;
            if (segments[i].indexOf('单') < 0 && segments[i].indexOf('双') < 0) continue;
            var into = -1;
            var near;
            for (near = i - 1; near >= 0; near--) {
                if (/[0-9]/.test(segments[near])) { into = near; break; }
            }
            for (near = i + 1; into < 0 && near < segments.length; near++) {
                if (/[0-9]/.test(segments[near])) { into = near; break; }
            }
            if (into < 0) { orphanMarkers++; continue; }
            segments[into] = segments[into] + segments[i];
            segments[i] = '';
        }
        var seen = {};
        var weeks = [];
        for (i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment || !/[0-9]/.test(segment)) continue;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            // 「单双周」两个标记都在 = 每周都上。两边互相排除会让整段周次变空，那才是真丢数据。
            if (onlyOdd && onlyEven) { onlyOdd = false; onlyEven = false; }
            var numbers = segment.match(/[0-9]+/g) || [];
            if (!numbers.length) continue;
            var start = parseInt(numbers[0], 10);
            var end = numbers.length > 1 ? parseInt(numbers[1], 10) : start;
            if (isNaN(start) || isNaN(end)) continue;
            if (end < start) { var swap = start; start = end; end = swap; }
            for (var week = start; week <= end; week++) {
                if (onlyOdd && week % 2 === 0) continue;
                if (onlyEven && week % 2 === 1) continue;
                if (week < 1 || week > MAX_WEEK) { droppedWeeks++; continue; }
                if (!seen[week]) { seen[week] = true; weeks.push(week); }
            }
        }
        weeks.sort(function (a, b) { return a - b; });
        return weeks;
    }

    // 周次集合 → 极大段：步长 1 视作每周，步长 2 视作单周 / 双周（手册 §4.1）。
    // 一个上游 course 切出多段就写成多个 block，语义等价。
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j], weekType: 'ALL' };
            if (step === 2 && run.start !== run.end) {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // 节次文本 → {start, end, sparse}；读不出来返回 null（调用方计数进 warnings，不静默丢课）。
    // 认得的写法：正方的 "1-2" / "3-4节" / "第9-10节" / "1,2"，以及个别部署把每节占两位拼成的
    // "0102"（= 第 1、2 节）。括号里的纯数字是序号不是节次，先摘掉。
    var sparseRows = 0;   // 节次写成多段（"1-2,5-6"）的行数
    function sectionsOf(row) {
        var source = text(row.jcs) || text(row.jc);
        if (!source) return null;
        var cleaned = source.replace(/[(（]\s*[0-9]+\s*[)）]/g, '')
            .replace(/节/g, '')
            .replace(/\s+/g, '');
        var pieces = cleaned.split(/[,，、;；]+/);
        var numbers = [];
        var i;
        for (i = 0; i < pieces.length; i++) {
            var piece = pieces[i];
            if (!piece) continue;
            var range = /^第?([0-9]{1,2})[-~至—－]([0-9]{1,2})$/.exec(piece);
            if (range) {
                var from = parseInt(range[1], 10);
                var to = parseInt(range[2], 10);
                if (to < from) { var swap = from; from = to; to = swap; }
                for (var n = from; n <= to; n++) numbers.push(n);
                continue;
            }
            if (/^第?[0-9]{1,2}$/.test(piece)) {
                numbers.push(parseInt(piece.replace(/[^0-9]/g, ''), 10));
                continue;
            }
            if (/^[0-9]{4}$/.test(piece)) {
                numbers.push(parseInt(piece.substring(0, 2), 10));
                numbers.push(parseInt(piece.substring(2), 10));
                continue;
            }
            return null;
        }
        if (!numbers.length) return null;
        var start = numbers[0];
        var end = numbers[0];
        var unique = [];
        var seen = {};
        for (i = 0; i < numbers.length; i++) {
            var value = numbers[i];
            if (value < start) start = value;
            if (value > end) end = value;
            if (!seen[value]) { seen[value] = true; unique.push(value); }
        }
        if (start < 1 || end > MAX_PERIOD) return null;
        unique.sort(function (a, b) { return a - b; });
        var sparse = false;
        for (i = 1; i < unique.length; i++) {
            if (unique[i] - unique[i - 1] !== 1) sparse = true;
        }
        if (sparse) sparseRows++;
        return { start: start, end: end, sparse: sparse };
    }

    // ---------- 教务的学期周次校历与作息时间（extract.js 另取的，取不到就是 null） ----------
    // 校历：一行一周，zs / zsmc = 第几周，日期字段各部署叫法不一（zrq / zcrq / rq / ksrq）
    function calendarInfo(value) {
        var list = rowsIn(value);
        var info = { firstIso: null, weeks: 0, rejectedIso: null };
        for (var i = 0; i < list.length; i++) {
            var row = list[i] || {};
            var week = intOf(row.zs);
            if (week === null) week = intOf(row.zsmc);
            var iso = isoOfAny(row.zrq) || isoOfAny(row.zcrq) || isoOfAny(row.rq) || isoOfAny(row.ksrq);
            if (week === null || week < 1 || !iso) continue;
            if (week > info.weeks) info.weeks = week;
            if (week === 1) {
                if (plausibleStart(iso)) info.firstIso = iso;
                else info.rejectedIso = iso;
            }
        }
        return info;
    }

    // 作息：一行一节，jcmc / jc = 节次，qssj / jssj = 起止时间。要求从第 1 节起连续、
    // 时间递增不重叠 —— 不满足就当没取到（回落到内置表并说明），不猜。
    function periodTimesOf(value) {
        var list = rowsIn(value);
        var slots = [];
        for (var i = 0; i < list.length; i++) {
            var row = list[i] || {};
            var index = intOf(row.jcmc);
            if (index === null) index = intOf(row.jc);
            var start = timeOf(row.qssj);
            var end = timeOf(row.jssj);
            if (index === null || !start || !end) return null;
            if (minutesOf(start) >= minutesOf(end)) return null;
            slots.push({ periodIndex: index, start: start, end: end });
        }
        if (!slots.length) return null;
        slots.sort(function (a, b) { return a.periodIndex - b.periodIndex; });
        var lastEnd = -1;
        for (var k = 0; k < slots.length; k++) {
            if (slots[k].periodIndex !== k + 1) return null;
            if (minutesOf(slots[k].start) < lastEnd) return null;
            lastEnd = minutesOf(slots[k].end);
        }
        return slots;
    }

    // ---------- 逐行转换 ----------
    var order = [];
    var byCourse = {};
    var missing = { name: 0, day: 0, period: 0, week: 0 };
    var maxWeek = 0;
    var maxPeriod = 0;

    for (var r = 0; r < rows.length; r++) {
        var row = rows[r] || {};

        var name = text(row.kcmc);
        if (!name) { missing.name++; continue; }

        var day = intOf(row.xqj);
        if (!(day >= 1 && day <= 7)) { missing.day++; continue; }

        var sections = sectionsOf(row);
        if (!sections) { missing.period++; continue; }

        var weeks = weeksOf(row.zcd);
        if (!weeks.length) { missing.week++; continue; }

        // 教师、教室没有就让它是空的（上游写的「未知」「未排地点」在课表里会当成真名显示）
        var teacher = text(row.xm) || null;
        var location = text(row.cdmc) || text(row.cdbh) || null;
        var key = name + SEP + (teacher || '');
        var course = byCourse[key];
        if (!course) {
            course = { name: name, teacher: teacher, note: null, blocks: [], seen: {} };
            byCourse[key] = course;
            order.push(key);
        }

        var runs = runsOf(weeks);
        for (var k = 0; k < runs.length; k++) {
            var run = runs[k];
            var blockKey = day + '|' + sections.start + '|' + sections.end + '|' + run.start + '|' +
                run.end + '|' + run.weekType + '|' + (location || '');
            if (course.seen[blockKey]) continue;   // 完全重复的排课行：同一条安排只留一条
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            if (sections.end > maxPeriod) maxPeriod = sections.end;
            course.blocks.push({
                dayOfWeek: day,
                startPeriod: sections.start,
                endPeriod: sections.end,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: location
            });
        }
    }

    if (!order.length) {
        throw new Error(
            '这个学期没有解析到任何课程：可能还没排课，也可能登录状态已失效。' +
            '请重新登录、打开「信息查询 - 学生课表查询」确认能看到课表后再试'
        );
    }

    var courses = [];
    for (var c = 0; c < order.length; c++) {
        var built = byCourse[order[c]];
        courses.push({ name: built.name, teacher: built.teacher, note: built.note, blocks: built.blocks });
    }

    // ---------- 学期名 / 开学日 / 总周数 ----------
    var name0 = termName();
    var todayIso = isoOfAny(data.today) || localTodayIso();
    var calendar = calendarInfo(data.calendar);
    var apiPeriodTimes = periodTimesOf(data.periodTimes);

    var start;
    if (calendar.firstIso) {
        start = { iso: mondayOfIso(calendar.firstIso), rule: '教务校历' };
    } else {
        start = estimateStart(todayIso);
    }

    // 正文接口不给总周数（上游同口径取课表里最大的周次）。校历给得出就先用校历，
    // 但课表里更晚的周次必须能放下，抬高时单独说明（否则那几周的课会被静默丢掉）。
    var calendarWeeks = calendar.weeks > 0 ? calendar.weeks : 0;
    var totalWeeks = calendarWeeks > 0 ? calendarWeeks : (maxWeek > 0 ? maxWeek : FALLBACK_TOTAL_WEEKS);
    var raisedBySchedule = false;
    var clamped = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) { totalWeeks = MAX_WEEK; clamped = true; }
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;

    // ---------- 作息时间：教务的优先，缺的节次补出来 ----------
    var periodTimes = apiPeriodTimes ? apiPeriodTimes.slice(0) : SCHOOL_PERIOD_TIMES.slice(0);
    var tableLength = apiPeriodTimes ? apiPeriodTimes.length : SCHOOL_PERIOD_TIMES.length;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    for (var p = tableLength + 1; p <= maxPeriod; p++) {
        var fallbackSlot = BUILTIN_PERIOD_TIMES[p - 1];
        if (fallbackSlot) {
            periodTimes.push({ periodIndex: p, start: fallbackSlot.start, end: fallbackSlot.end });
            extendedTo = p;
        } else if (!uncoveredFrom) {
            uncoveredFrom = p;
        }
    }

    // ---------- warnings（顺序固定：算出来的、猜出来的、丢掉的都要说清楚） ----------
    var warnings = [];

    warnings.push(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (calendar.firstIso) {
        warnings.push(
            '开学日期取自教务系统的学期周次校历：第 1 周从 ' + start.iso + ' 开始，请在学期管理里核对'
        );
    } else if (calendar.rejectedIso) {
        warnings.push(
            '教务校历给出的开学日期（' + calendar.rejectedIso + '）不属于这个学期，已忽略；第 1 周按「' +
            start.rule + '」推算为 ' + start.iso + '，请在学期管理里核对成学校实际开学日'
        );
    } else {
        warnings.push(
            '教务系统没有给出开学日期（学期周次校历接口没有返回可用数据），第 1 周按「' + start.rule +
            '」推算为 ' + start.iso + '，请在学期管理里核对成学校实际开学日'
        );
    }

    if (raisedBySchedule) {
        warnings.push(
            '教务校历写的一学期是 ' + calendarWeeks + ' 周，课表里有第 ' + maxWeek + ' 周的课，已按 ' +
            totalWeeks + ' 周导入（否则第 ' + (calendarWeeks + 1) + ' 周起的课放不下）'
        );
    } else if (calendarWeeks > 0) {
        warnings.push(
            '学期总周数取自教务校历（' + totalWeeks + ' 周）' +
            (clamped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') + '，如与实际不符可在学期管理里改'
        );
    } else {
        warnings.push(
            '学期总周数取的是课表里出现的最大周次（' + totalWeeks + ' 周），不是校历周数' +
            '（学期周次校历接口没有返回可用数据），如与实际不符可在学期管理里改'
        );
    }

    if (!apiPeriodTimes) {
        warnings.push(
            '教务的作息时间接口没有返回可用的作息表，已用适配器内置的 ' + SCHOOL_PERIOD_TIMES.length +
            ' 节作息表（第 1 节 ' + SCHOOL_PERIOD_TIMES[0].start + '-' + SCHOOL_PERIOD_TIMES[0].end +
            '），时间可能与学校实际作息不符，请在学期管理里核对'
        );
    }
    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) notes.push('第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间');
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warnings.push(
            '课表里用到第 ' + maxPeriod + ' 节，而作息表只到第 ' + tableLength + ' 节：' +
            notes.join('；') + '，请核对'
        );
    }

    // 取数是否取全：正方这个接口一次性返回整学期排课，本来没有分页；但响应里带了记录总数时
    // 必须对账 —— 取到的比总数少说明响应被截断/被挡了，要出声（手册：取不全要说）
    var reportedTotal = intOf(data.total);
    if (reportedTotal !== null && reportedTotal > rows.length) {
        warnings.push(
            '教务系统说这个学期有 ' + reportedTotal + ' 条排课记录，实际只取到 ' + rows.length +
            ' 条，课表可能不完整，请重试或反馈'
        );
    }

    // 集中实践课：教务单独给的一批课，只有课名与起止周，没有星期节次，排不进周课表 —— 但不能吞掉
    var practiceNames = [];
    for (var q = 0; q < practiceRows.length; q++) {
        var practiceName = text(practiceRows[q] && practiceRows[q].kcmc);
        if (practiceName && practiceNames.indexOf(practiceName) < 0) practiceNames.push(practiceName);
    }
    if (practiceNames.length) {
        var shown = practiceNames.slice(0, 3).join('、');
        if (practiceNames.length > 3) shown = shown + ' 等';
        warnings.push(
            '教务系统另外给了 ' + practiceNames.length + ' 门集中实践课（' + shown +
            '）：它们没有固定的星期与节次，排不进周课表，请自行在学期里添加'
        );
    }

    var skipped = missing.name + missing.day + missing.period + missing.week;
    if (skipped > 0) {
        var reasons = [];
        if (missing.name) reasons.push('缺课程名 ' + missing.name + ' 行');
        if (missing.day) reasons.push('缺星期 ' + missing.day + ' 行');
        if (missing.period) reasons.push('缺节次 ' + missing.period + ' 行');
        if (missing.week) reasons.push('缺周次（或周次全超出 1-' + MAX_WEEK + ' 周）' + missing.week + ' 行');
        warnings.push(
            '有 ' + skipped + ' 行课表数据不全（' + reasons.join('、') +
            '），已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (orphanMarkers > 0) {
        warnings.push('有 ' + orphanMarkers + ' 处「单/双」标记找不到对应的周次（教务数据写得不完整），这些标记已忽略');
    }
    if (sparseRows > 0) {
        warnings.push(
            '有 ' + sparseRows + ' 行课表的节次写成了多段（例如「1-2,5-6」），已按最大跨度放入' +
            '（中间几节也算上），请核对'
        );
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: name0,
                firstDay: start.iso,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
