(function () {
    // 重庆化工职业学院教务适配器（正方新版 jwglxt 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 CQCIVC/cqcivc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 上游在同一个脚本里算周次、拼课程、存配置；这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑），
    // 取数全部在 extract.js 里，交出来的就是教务的行对象。
    //
    // 移植改动（逐条对着移植手册 §4 与第三批专项检查表）：
    //   ① 周次解析按本校自己的写法重写：上游只认 "1-16周(单)" 这一种（把 "(单)" 当独立子串找），
    //      另外三种写法在它手里是**静默丢数据** ——
    //        "1-16(单周)"：正则 (数字)-(数字)周 要求「周」紧跟第二个数字，"1-16(单周)" 与 "/^数字周/"
    //                      两条都不匹配 → 这门课一个周次都解析不出来 → 整行被 continue 掉（丢课）
    //        "1-3,5-9周" ：逗号切开后 "1-3" 那段没有「周」字 → 同样解析不出 → 前 3 周被丢掉
    //        "(单)1-16周"：上游的 includes('(单)') 恰好认得出，但标记写在「周」后时会被切走
    //      本件先把括号里的纯数字（教学班序号）摘掉、再把量词「周」整段删掉，然后才切段，
    //      四种写法都得到正确周次（用例见 fixtures/weeks-forms.*）
    //   ② 上游读不出 kcmc / xm / cdmc / xqj / jcs / zcd 里**任意一个**就整行 continue（静默丢课）：
    //      教室或教师为空的课（网课、待排教室）会整门消失。本件只要求课程名非空，
    //      教师 / 教室没有就留空，脏行按四类分别计数写进 warnings
    //   ③ 开学日：上游**完全没给**（config 里连 semesterStartDate 字段都没有），
    //      本件按学期序号推算并如实写进 warnings（手册 §4.2 的硬要求）
    //   ④ 总周数：上游写死 20（字段名还写成了 totalWeeks，手册 §4.5 记过这个坑）。
    //      本件以 20 为默认，但课表里更晚的周次必须放得下（否则那几周的课会被载荷校验整包拒掉）
    //   ⑤ 节次：上游只 split('-') 取首尾，"3-4节" / "0102" 会解析成 NaN 再被静默丢掉；
    //      本件认区间 / 多段 / 两位一拼，多段按最大跨度放入并出声
    //   ⑥ 作息时间沿用上游那张本校作息表（11 节），课表用到更晚的节次时按空课内建节次表补齐
    //   ⑦ 教师 / 教室没有就留空：上游给 cdmc 加了必填判断，缺了直接丢课
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = Array.isArray(raw.kbList) ? raw.kbList : [];
    var practiceRows = Array.isArray(raw.sjkList) ? raw.sjkList : [];

    // 复合键（课名 + 教师）的分隔符取 NUL。用 String.fromCharCode 取，源码里不出现控制字符、
    // 也不出现转义序列 —— 第一批有两个适配器把转义序列落成了真的 NUL 字节，文件被 grep 当二进制看。
    var SEP = String.fromCharCode(0);

    var MAX_WEEK = 30;             // 载荷校验：totalWeeks ∈ 1..30
    var MAX_PERIOD = 20;           // 单日节次上限：超过它一定是脏数据（本校作息只有 11 节）
    var DEFAULT_TOTAL_WEEKS = 20;  // 上游写死的 semesterTotalWeeks（教务不给总周数）

    // 学校作息（重庆化工职业学院）：取自上游 CQCIVC/cqcivc.js 里那张预设节次表，原样搬过来。
    // 上游每次导入都把它当「统一作息时间」写入，所以本件直接带上，并写进 warnings 让用户核对。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:10' },
        { periodIndex: 2, start: '09:20', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:00' },
        { periodIndex: 4, start: '11:10', end: '11:50' },
        { periodIndex: 5, start: '14:00', end: '14:40' },
        { periodIndex: 6, start: '14:50', end: '15:30' },
        { periodIndex: 7, start: '15:40', end: '16:20' },
        { periodIndex: 8, start: '16:30', end: '17:10' },
        { periodIndex: 9, start: '18:30', end: '19:10' },
        { periodIndex: 10, start: '19:20', end: '20:00' },
        { periodIndex: 11, start: '20:10', end: '20:50' }
    ];

    // 空课内置节次表（:core:model 的 DefaultPeriodTimes，12 节）：课表用到的节次超出本校作息表时
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

    var KIND_CN = { first: '一', second: '二' };
    // 推算开学日的锚点：中国高校第一学期多在 9 月初、第二学期多在 2 月下旬。
    // 别直接把锚点当 firstDay：手册 §4.3 要求回退到「第 1 周的第一天」那个周一（含当天）。
    var KIND_ANCHOR = {
        first: { month: 9, day: 1, rule: '第一学期 = 9 月 1 日所在周的周一' },
        second: { month: 2, day: 20, rule: '第二学期 = 2 月 20 日所在周的周一' }
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

    // ---------- 学期名与学期序号 ----------
    // 优先看教务下拉框的文本（「一/二」），认不出来再看正方自己的学期代号
    // （正方 jwglxt：3 = 第一学期、12 = 第二学期；上游也是把用户选的下标映射成这两个码）
    function termKind() {
        var label = text(term.xqmText);
        if (label.indexOf('二') >= 0) return 'second';
        if (label.indexOf('一') >= 0) return 'first';
        var code = text(term.xqm);
        if (code === '12' || code === '2') return 'second';
        return 'first';
    }

    function termYear() {
        return /^[0-9]{4}$/.test(text(term.xnm)) ? parseInt(text(term.xnm), 10) : 0;
    }

    function yearLabel() {
        // 教务下拉框的文本可能是「2026-2027」，也可能带后缀（「2026-2027学年」）或干脆是别的写法，
        // 认不出来就用 xnm 自己拼 —— 后端只有这条路要走（xnm 读不出来时才退回原样文本）
        var label = text(term.xnmText);
        if (/^[0-9]{4}-[0-9]{2,4}$/.test(label)) return label;
        var year = termYear();
        if (year) return year + '-' + (year + 1);
        return label || text(term.xnm);
    }

    // 学期名用教务自己的学年学期（手册 §4.7：别拿适配器名当学期名）
    function termName() {
        var season = text(term.xqmText);
        if (season.indexOf('学期') < 0) season = '第' + KIND_CN[termKind()] + '学期';
        return yearLabel() + '学年' + season;
    }

    // 教务不给开学日时的推算。依赖学期序号的锚点，推算结果一律写进 warnings。
    function estimateStart(todayIso) {
        var year = termYear();
        var kind = termKind();
        if (!year) {
            // 学年读不出来（课表页被改过）时退回「今天的周一」。这条分支依赖当天日期，
            // 不要写进 fixture 用例 —— 用例会随日期失效
            return { iso: mondayOfIso(todayIso) || todayIso, rule: '今天的周一' };
        }
        return {
            iso: isoOf(mondayOnOrBefore(year + (kind === 'second' ? 1 : 0), KIND_ANCHOR[kind].month, KIND_ANCHOR[kind].day)),
            rule: KIND_ANCHOR[kind].rule
        };
    }

    // 周次文本 → 周次集合（去重、升序）。本校教务（正方）的写法：
    //   "1-16周" / "1-16周(单)" / "1-16周(双)" / "(单)1-16周" / "1-16(单周)" / "1-3,5-9周"
    // 三个坑（第一个来自上游这份脚本本身，后两个是第一/二批实测出来的）：
    //   ① 上游正则要求「周」紧跟第二个数字，"1-16(单周)" 因此一个周次都解析不出、整行被丢掉；
    //      逗号列表里漏写「周」的那段（"1-3,5-9周" 的 "1-3"）同样解析不出，前几周静默消失；
    //   ② 「周」是量词：留着它，"1-16周(单)" 里紧跟其后的 "(单)" 会被切成另一段，单/双标记整段丢掉，
    //      这门课退化成「每周都上」而且一声不吭 —— 所以先把「周」整个删掉再切段；
    //   ③ 括号里的纯数字是教学班序号（"(1)" / "(1-2)"）不是周次，先整段摘掉，
    //      免得它被当成「第 1 周」或「第 1-2 周」。
    var droppedWeeks = 0;   // 超出 1..MAX_WEEK 被丢掉的周次个数
    var orphanMarkers = 0;  // 带单/双标记但找不到任何周次段的段数

    function weeksOf(source) {
        // ④ 破折号**连同它两侧的空白**一起归一成半角 '-'：教务偶尔把区间写成 "1 -16周" / "1- 16周"
        //    （破折号旁多一个空格），不归一就会在切段时被劈成孤立周次 —— "1 -16周(单)" 退化成
        //    「第 1 周每周都上」（单周标记被并到孤立的 "1" 上）、"1- 16周" 变成 "1" + "16" 两块。
        //    做法与上游同平台的 CFEC / wenhua_01 / zjut_01 / huel_01 一致。
        //    **注意：不要全局删空白** —— 空白在本域里同时是分段符（"1-16周 双"、"1-3周 5-9周"），
        //    全文剥空白会把 "1-3周 5-9周" 拼成 "1-35" → 变成 1-30 全周，静默吞掉一段。
        //    text() 已把各类空白折成单个空格，所以这里用空格表示「空白」。
        var cleaned = text(source);
        if (!cleaned) return [];
        // ⑤ 全角/异体连接号（含「至」「到」）统一成 '-'："1～16周" "1－16周" "1−16周" "1–16周"
        //    "1—16周" "1至16周" "1到16周"。不归一的话含义相同的括号序号 "(1～2)" 会漏摘
        //    （括号序号的正则只认 ASCII 那几种），被当成「第 1-2 周」加进周次。
        cleaned = cleaned
            .replace(/ *[-—–−－~～至到] */g, '-')
            .replace(/[（(]/g, '(')
            .replace(/[）)]/g, ')')
            .replace(/[，、;；]/g, ',')
            .replace(/\([0-9]+(?:\s*[-~—－]\s*[0-9]+)?\)/g, '')
            .replace(/周/g, '')
            .replace(/[()]/g, '');
        var pieces = cleaned.split(/[,\s]+/);
        // 空白**仍是分段符**：破折号连同两侧空白已在 ④ 里整段归一掉，这里剩下的空白与逗号一起
        // 当分隔符 —— "1-3周 5-9周" 切成两段（不会被拼成一个区间），"1-16周 双" 切成周次段 + 标记段。
        var segments = [];
        var i;
        for (i = 0; i < pieces.length; i++) {
            if (pieces[i]) segments.push(pieces[i]);
        }
        // 单/双标记自己成一段（"1-16 双"）时并回最近的周次段，不丢
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
    // 上游只 split('-') 取首尾：读到 "3-4节" 时第二个数是 NaN，整行被静默丢掉。
    var sparseRows = 0;   // 节次写成多段（"1-2,5-6"）的行数
    function sectionsOf(row) {
        var source = text(row.jcs);
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
        return { start: start, end: end };
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

        // 教师、教室没有就让它是空的（上游把它们当必填，缺一个就整行丢课；写「未知」又会当成真名显示）
        var teacher = text(row.xm) || null;
        var location = text(row.cdmc) || null;
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
    var start = estimateStart(todayIso);

    // 教务接口不给总周数（上游写死 20）。默认 20 周，但课表里更晚的周次必须能放下 ——
    // 否则 endWeek 超出 totalWeeks，整个载荷会被校验拒掉（不是丢那几节课，是整包导不进来）。
    var totalWeeks = DEFAULT_TOTAL_WEEKS;
    var raisedBySchedule = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    // ---------- 作息时间：本校作息表，缺的节次补出来 ----------
    var periodTimes = SCHOOL_PERIOD_TIMES.slice(0);
    var tableLength = SCHOOL_PERIOD_TIMES.length;
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

    warnings.push(
        '教务系统没有给出开学日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
        '，请在学期管理里核对成学校实际开学日'
    );

    if (raisedBySchedule) {
        warnings.push(
            '适配器默认一学期 ' + DEFAULT_TOTAL_WEEKS + ' 周，课表里有第 ' + maxWeek + ' 周的课，已按 ' +
            totalWeeks + ' 周导入（否则第 ' + (DEFAULT_TOTAL_WEEKS + 1) + ' 周起的课放不下），如与实际不符可在学期管理里改'
        );
    } else {
        warnings.push(
            '学期总周数用的是适配器默认的 ' + totalWeeks + ' 周（教务没有给出总周数），' +
            '如与学校实际不符可在学期管理里改'
        );
    }

    warnings.push(
        '作息时间用的是适配器内置的本校作息表（' + tableLength + ' 节，第 1 节 ' +
        SCHOOL_PERIOD_TIMES[0].start + '-' + SCHOOL_PERIOD_TIMES[0].end +
        '），如与学校实际作息不符请在学期管理里核对'
    );

    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) {
            notes.push(extendedTo > tableLength + 1
                ? '第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间'
                : '第 ' + extendedTo + ' 节按空课内建节次表补了时间');
        }
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warnings.push(
            '课表里用到第 ' + maxPeriod + ' 节，而本校作息表只到第 ' + tableLength + ' 节：' +
            notes.join('；') + '，请核对'
        );
    }

    // 取数是否取全：正方这个接口一次性返回整学期排课，本来没有分页；但响应里带了记录总数时
    // 必须对账 —— 取到的比总数少说明响应被截断/被挡了，要出声（检查表第 6 条）
    var reportedTotal = intOf(data.total);
    if (reportedTotal !== null && reportedTotal > rows.length) {
        warnings.push(
            '教务系统说这个学期有 ' + reportedTotal + ' 条排课记录，实际只取到 ' + rows.length +
            ' 条，课表可能不完整，请重试或反馈'
        );
    }

    // 集中实践课：教务单独给的一批课，只有课名与起止周，没有星期节次，排不进周课表 —— 但不能吞掉
    // （上游 cqcivc 完全没读这个字段；extract.js 只是把响应里已经拿到的这一项原样带出来）
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
