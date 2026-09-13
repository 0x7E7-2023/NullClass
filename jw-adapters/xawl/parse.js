(function () {
    // 西安文理学院教务适配器（正方新版 jwglxt 平台）—— 第二步：教务原始行 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 XAWL/xawl_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 上游把取数、解析、拼课程、存作息塞在同一段脚本里；这里只做纯转换
    // （不碰页面、不发请求，CI 用 Rhino 实跑），取数全在 extract.js 里，交出来的就是教务的行对象。
    // 同族（正方 jwglxt）不等于同编码：周次写法、节次写法、作息表都按 XAWL 上游脚本自己的数据，
    // 单独造了 3 对 fixture（见 docs/jw-adapter-testing.md §3.2）。
    //
    // 移植改动（逐条对照移植手册 §4 与本批检查表）：
    //   ① 周次解析加固。上游 parseWeeks 按逗号切段后要求「数字（范围）紧跟着一个周字」，
    //      单/双标记还必须恰好写成半角「(单)」「(双)」。把上游那段函数原样跑一遍，实测：
    //        "1-16(单周)"  → []       整门课静默消失
    //        "1-3,5-9周"   → [5..9]   前一段 1-3 静默丢失
    //        "1-16周,双周" → 1..16    双周标记静默丢掉，塌成「每周都上」
    //      这里先把「周」整个删掉再切段，上面几种写法都能认；认不出的标记计数进 warnings。
    //      （上游能认对的 "1-16周(单)" / "(单)1-16周" / "1-16周(双)" 移植后结果不变。）
    //   ② 括号里的纯数字是教学班序号（"(1)"、"(1-2)"），不是周次：括号外还有周次数字就摘掉它，
    //      整串只剩序号时才判定「这一行没有周次」并单独说明 —— 不猜、不静默。
    //   ③ 上游读不出课程名 / 星期 / 节次 / 周次的行直接 continue（静默丢课）：这里逐类计数写进 warnings。
    //   ④ 节次写法从上游的 split('-') 放宽到 "1-2" / "第9-10节" / "1,2" / 两位一拼的 "0102"，
    //      多段（"1-2,5-6"）按最大跨度放入并写进 warnings（不静默丢，也不静默改）。
    //   ⑤ 开学日与总周数：这个课表接口都不给（上游也没有），按学期序号推算开学日、按课表里最大的
    //      周次定总周数，两条都如实写进 warnings —— 推算值和真值在库里长得一模一样，不说用户没机会发现。
    //   ⑥ 作息时间：上游让用户在「夏季 / 非夏季」里二选一，这里改成直接按非夏季作息写入，
    //      并把夏季作息的差异写进 warnings（移植手册 §3 第 1 步：能自动就别打断用户；
    //      作息表在学期管理里随时可换）。原文两张表都取自上游，数值未改。
    //   ⑦ 教师 / 教室没有就留空：上游的兜底值（「未知」之类）在课表里会被当成真名显示。
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var rows = Array.isArray(data.rows) ? data.rows : [];

    // 复合键（课名 + 教师）的分隔符取 NUL。用 String.fromCharCode 取，源码里既没有控制字符
    // 也没有转义序列 —— 第一批有两个适配器把转义序列落成了真的 NUL 字节，文件被 grep 当二进制看。
    var SEP = String.fromCharCode(0);

    var MAX_WEEK = 30;             // 载荷校验：totalWeeks ∈ 1..30
    var MAX_PERIOD = 12;           // 单日节次上限：超过它一定是脏数据（学校作息只有 10 节）
    var FALLBACK_TOTAL_WEEKS = 20; // 课表一行都没解析出来时不会走到这里，纯兜底
    var MAX_WARNINGS = 20;         // 载荷校验：warnings ≤ 20 条
    var MAX_WARNING_TEXT = 200;    // 载荷校验：单条 ≤ 200 字

    // 学校作息（西安文理学院）：两张表都原样取自上游 xawl_01.js 的 Non_summerTimeSlots /
    // SummerTimeSlots，数值未做任何改动。写入载荷的是非夏季表（上游在用户取消选择时的默认值也是它）。
    var NON_SUMMER_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:50' },
        { periodIndex: 2, start: '09:00', end: '09:50' },
        { periodIndex: 3, start: '10:10', end: '11:00' },
        { periodIndex: 4, start: '11:10', end: '12:00' },
        { periodIndex: 5, start: '14:00', end: '14:50' },
        { periodIndex: 6, start: '15:00', end: '15:50' },
        { periodIndex: 7, start: '16:10', end: '17:00' },
        { periodIndex: 8, start: '17:10', end: '18:00' },
        { periodIndex: 9, start: '19:00', end: '19:50' },
        { periodIndex: 10, start: '20:00', end: '20:50' }
    ];
    var SUMMER_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:50' },
        { periodIndex: 2, start: '09:00', end: '09:50' },
        { periodIndex: 3, start: '10:10', end: '11:00' },
        { periodIndex: 4, start: '11:10', end: '12:00' },
        { periodIndex: 5, start: '14:30', end: '15:20' },
        { periodIndex: 6, start: '15:30', end: '16:20' },
        { periodIndex: 7, start: '16:40', end: '17:30' },
        { periodIndex: 8, start: '17:40', end: '18:30' },
        { periodIndex: 9, start: '19:30', end: '20:20' },
        { periodIndex: 10, start: '20:30', end: '21:20' }
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
    // 推算开学日的锚点：中国高校第一学期多在 9 月初、第二学期多在 2 月下旬、第三学期（夏季）7 月初。
    // 锚点不是 firstDay —— 手册 §4.3 要求回退到「第 1 周的第一天」那个周一（含当天）。
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

    // "08:10" / "08:10:00" → "08:10"；认不出来（含 24:00 / 85:45 这类越界值）返回 null
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // ---------- 学期名与学期序号 ----------
    // 优先看课表页下拉框的文本（「一/二/三」），认不出来再看正方自己的学期代号
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
        // 有的部署把整个「2026-2027学年第一学期」放进学期下拉框
        if (whole && whole.indexOf('学年') >= 0 && whole.indexOf('学期') >= 0) return whole;
        return yearLabel() + '学年第' + KIND_CN[termKind()] + '学期';
    }

    // 这个接口不给开学日（也没有校历接口），只能按学期序号推算；推算结果一律写进 warnings。
    function estimateStart(todayIso) {
        var kind = termKind();
        var anchor = KIND_ANCHOR[kind];
        var year = termYear();
        if (!year) {
            // 学年读不出来（页面被改过）时退回「今天的周一」。这条分支依赖当天日期，
            // 不写进 fixture 用例 —— 用例会随日期失效
            return { iso: mondayOfIso(todayIso) || todayIso, rule: '今天的周一' };
        }
        return {
            iso: isoOf(mondayOnOrBefore(year + (kind === 'first' ? 0 : 1), anchor.month, anchor.day)),
            rule: anchor.rule
        };
    }

    // ---------- 周次 ----------
    // 周次文本 → 周次集合（去重、升序）。XAWL 走的是正方 jwglxt 自己的写法：
    //   "1-16周" / "1-16周(单)" / "(单)1-16周" / "1-16(单周)" / "1-3,5-9周" / "17周"
    // 五个坑：
    //   ① 「周」是量词：留着它，"1-16周(单)" 里紧跟其后的 "(单)" 会被当成另一段，单/双标记整段丢掉，
    //      这门课就退化成「每周都上」而且一声不吭 —— 所以先把「周」整个删掉再切段；
    //   ② 单/双标记的四种写法都要认；标记也可能自己成一段（"1-16周,双周"），并给最近的周次段，不丢；
    //   ③ 括号里的纯数字是教学班序号（"(1)"、"(1-2)"）不是周次。括号外还有周次数字才摘得放心；
    //      整串只剩序号（zcd 就是 "(1)"）时没有周次可用 —— 这一行单独计数并说明，不猜成第 1 周；
    //   ④ 超出 1..MAX_WEEK 的周次丢弃并计数（不静默）。
    //   ⑤ 破折号两侧的空白会切开区间（"1 -16周(单)" / "1- 16周"）—— 先归一破折号再切段，
    //      细节与反例写在 weeksOf 里。
    var droppedWeeks = 0;    // 超出 1..MAX_WEEK 被丢掉的周次个数
    var orphanMarkers = 0;   // 带单/双标记但找不到任何周次段的段数
    var serialOnlyRows = 0;  // zcd 整串只有括号里的教学班序号、没有周次的行数

    function weeksOf(source) {
        var cleaned = text(source);
        if (!cleaned) return [];
        // ⑤ 破折号两侧只要有一个空格，区间就会被切段切开，而且切得静悄悄：
        //      "1 -16周(单)" → "1" + "16周(单)"  前半段只剩第 1 周，「单」跟着后半段走掉，
        //                                        整门课塌成「只上第 1 周」（应为单周 1-15）
        //      "1- 16周"     → "1-" + "16"        两块孤立的 1 周与 16 周，中间 14 周静默丢失
        //    所以先把破折号（半角 / 全角 / 波浪 / 减号 / 汉字「至」）连同它两侧的空白一起归一成半角 "-"，
        //    再把**剩下的**空白当成段分隔补成逗号 —— 先剥空白再切段，区间再也不会被切开；
        //    空白仍保留分段语义，所以 "1-16周 双"（标记用空格分开）与 "1-3周 5-9周" 都还是两段，
        //    "1-16周" 这类标准写法结果不变。
        cleaned = cleaned.replace(/\s*[-~至—－−–～]\s*/g, '-');
        cleaned = cleaned.replace(/周/g, '');
        var withoutSerials = cleaned.replace(
            /[(（]\s*[0-9]+\s*(?:[-~至—－−–～]\s*[0-9]+\s*)?[)）]/g, ''
        );
        if (/[0-9]/.test(withoutSerials)) {
            cleaned = withoutSerials;
        } else if (/[(（]\s*[0-9]+\s*(?:[-~至—－−–～]\s*[0-9]+\s*)?[)）]/.test(cleaned)) {
            // 括号外一个数字都没有：整串就是教学班序号，没有周次可用
            serialOnlyRows++;
            return [];
        }
        cleaned = cleaned.replace(/[(（]/g, '').replace(/[)）]/g, '');
        cleaned = cleaned.replace(/\s+/g, ',');
        var tokens = cleaned.split(/[,，、;；]+/);
        var segments = [];
        var i;
        for (i = 0; i < tokens.length; i++) {
            if (tokens[i]) segments.push(tokens[i]);
        }
        // 单/双标记自己成一段时并给最近的周次段
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
    // 上游是 jcs.split('-') 取首尾：碰见 "0102" 会得到第 102 节这种越界值，这里换成逐段解析。
    // 认得的写法：正方的 "1-2" / "3-4节" / "第9-10节" / "1,2"，以及个别部署把每节占两位拼成的 "0102"。
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

    // 作息表兜底校验（检查表 4）：写进载荷的每个时间必须是 HH:mm 且 00:00-23:59、结束晚于开始。
    // 表是常量，正常一条都不该被改；万一以后有人手改错了，这里换掉它并出声，
    // 而不是让整个载荷被应用拒收（endPeriod 那类错误会连累全部课程）。
    var fixedSlots = 0;
    function sanitizeSlots(slots) {
        var out = [];
        for (var i = 0; i < slots.length; i++) {
            var slot = slots[i];
            var start = timeOf(slot.start);
            var end = timeOf(slot.end);
            if (!start || !end || minutesOf(start) >= minutesOf(end)) {
                var spare = BUILTIN_PERIOD_TIMES[slot.periodIndex - 1];
                if (!spare) { fixedSlots++; continue; }
                start = spare.start;
                end = spare.end;
                fixedSlots++;
            }
            out.push({ periodIndex: slot.periodIndex, start: start, end: end });
        }
        return out;
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

        // 教师、教室没有就留空（上游的兜底值在课表里会被当成真名显示）
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

    var name0 = termName();

    if (!rows.length) {
        throw new Error(
            '教务系统在「' + name0 + '」没有返回任何排课记录：可能是这个学期还没排课，' +
            '也可能是学年学期取得不对。请在教务系统里打开「信息查询 - 学生课表查询」，' +
            '确认能看到自己的课表后再点「提取课表」'
        );
    }
    if (!order.length) {
        throw new Error(
            '教务系统返回了 ' + rows.length + ' 条排课记录，但没有一条能解析成课程' +
            '（课名 / 星期 / 节次 / 周次字段对不上）：教务系统可能改过课表接口，请反馈'
        );
    }

    var courses = [];
    for (var c = 0; c < order.length; c++) {
        var built = byCourse[order[c]];
        courses.push({ name: built.name, teacher: built.teacher, note: built.note, blocks: built.blocks });
    }

    // ---------- 开学日 / 总周数 ----------
    var todayIso = isoOfAny(data.today) || localTodayIso();
    var start = estimateStart(todayIso);

    // 这个接口不给总周数（上游同口径：取课表里最大的周次）。
    var totalWeeks = maxWeek > 0 ? maxWeek : FALLBACK_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;

    // ---------- 作息时间：非夏季作息，缺的节次补出来 ----------
    var periodTimes = sanitizeSlots(NON_SUMMER_PERIOD_TIMES);
    var tableLength = periodTimes.length;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    for (var p = NON_SUMMER_PERIOD_TIMES.length + 1; p <= maxPeriod; p++) {
        var spare = BUILTIN_PERIOD_TIMES[p - 1];
        if (spare) {
            periodTimes.push({ periodIndex: p, start: spare.start, end: spare.end });
            extendedTo = p;
        } else if (!uncoveredFrom) {
            uncoveredFrom = p;
        }
    }

    // ---------- warnings（顺序固定：算出来的、猜出来的、丢掉的都要说清楚） ----------
    // 条数与长度都有载荷上限；这里逐条截断，且这条链上最多产生 13 条（远低于 20 条上限）。
    var warnings = [];
    function warn(message) {
        var value = text(message);
        if (value.length > MAX_WARNING_TEXT) value = value.substring(0, MAX_WARNING_TEXT - 1) + '…';
        if (warnings.length < MAX_WARNINGS) warnings.push(value);
    }

    if (text(term.source) === 'page') {
        warn('学年学期读的是课表页上当前选中的「' + name0 +
            '」；要导入别的学期，请在教务系统里把学年学期切到那个学期，再点「提取课表」');
    } else {
        warn('没能从课表页读到当前学年学期（页面上没有 #xnm / #xqm 下拉框），已按今天的日期推定为「' + name0 +
            '」；如果不对，请在教务系统里打开「信息查询 - 学生课表查询」并把学年学期选对，再点「提取课表」');
    }

    warn('教务系统没有给出开学日期（这个课表接口只返回周次编号，没有校历），第 1 周按「' + start.rule +
        '」推算为 ' + start.iso + '，请在学期管理里核对成学校实际开学日');

    warn('学期总周数取的是课表里出现的最大周次（' + totalWeeks +
        ' 周），不是校历周数（这个接口不给总周数），如与实际不符可在学期管理里改');

    warn('作息时间按学校的非夏季作息写入（第 1 节 ' + NON_SUMMER_PERIOD_TIMES[0].start + '-' +
        NON_SUMMER_PERIOD_TIMES[0].end + '，第 5 节 ' + NON_SUMMER_PERIOD_TIMES[4].start + ' 起）；' +
        '学校的夏季作息下午与晚上都更晚（第 5 节 ' + SUMMER_PERIOD_TIMES[4].start + ' 起、第 9 节 ' +
        SUMMER_PERIOD_TIMES[8].start + ' 起），如与课表时间不符请在学期管理里换一张作息表');

    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) notes.push('第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间');
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warn('课表里用到第 ' + maxPeriod + ' 节，而学校作息表只到第 ' + tableLength + ' 节：' +
            notes.join('；') + '，请核对');
    }
    if (fixedSlots > 0) {
        warn('有 ' + fixedSlots + ' 个作息时间不是合法的 HH:mm 或结束不晚于开始，已按空课内建节次表替换，请核对');
    }

    var rowsKey = text(data.rowsKey);
    if (rowsKey && rowsKey !== 'kbList') {
        warn('教务系统返回的课表字段名不是预期的 kbList（实际是「' + rowsKey +
            '」），本适配器按它读取了；如果发现课程不对请反馈');
    }

    // 取数是否取全：这个接口一次性返回整学期排课，本来没有分页；但响应里带了记录总数时
    // 必须对账 —— 取到的比总数少说明响应被截断或被挡了，要出声（检查表 6：取不全必须说）
    var reportedTotal = intOf(data.total);
    if (reportedTotal !== null && reportedTotal > rows.length) {
        warn('教务系统说这个学期有 ' + reportedTotal + ' 条排课记录，实际只取到 ' + rows.length +
            ' 条，课表可能不完整，请重试或反馈');
    }

    var skipped = missing.name + missing.day + missing.period + missing.week;
    if (skipped > 0) {
        var reasons = [];
        if (missing.name) reasons.push('缺课程名 ' + missing.name + ' 行');
        if (missing.day) reasons.push('缺星期 ' + missing.day + ' 行');
        if (missing.period) reasons.push('缺节次（或节次超出第 ' + MAX_PERIOD + ' 节）' + missing.period + ' 行');
        if (missing.week) reasons.push('缺周次（或周次全超出 1-' + MAX_WEEK + ' 周）' + missing.week + ' 行');
        warn('有 ' + skipped + ' 行课表数据不全（' + reasons.join('、') +
            '），已跳过：教务数据不完整时会出现，如发现少课请反馈');
    }
    if (serialOnlyRows > 0) {
        warn('有 ' + serialOnlyRows +
            ' 行课表的周次字段里只有括号里的教学班序号（例如「(1)」「(1-2)」），没有可用的周次，已跳过；' +
            '如发现少课请反馈');
    }
    if (droppedWeeks > 0) {
        warn('有 ' + droppedWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (orphanMarkers > 0) {
        warn('有 ' + orphanMarkers + ' 处「单/双」标记找不到对应的周次（教务数据写得不完整），这些标记已忽略');
    }
    if (sparseRows > 0) {
        warn('有 ' + sparseRows + ' 行课表的节次写成了多段（例如「1-2,5-6」），已按最大跨度放入' +
            '（中间几节也算上），请核对');
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
