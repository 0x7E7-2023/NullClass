(function () {
    // 青岛黄海学院教务适配器（正方新版 jwglxt 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 QDHHC/qdhhc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 上游在同一段脚本里算周次、拼课程、存配置；这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑），
    // 取数全部在 extract.js 里，交出来的就是教务的行对象（kbList）。
    //
    // 本件的上游代码比同族其它件大，难的地方集中在两处：① 周次字符串的分支很多；
    // ② 课程合并分两阶段（先并连堂节次、再并同节次的周次）。两处都原样移植，并按本校的写法造了用例。
    //
    // 移植改动（按移植手册 §4 与本批检查表逐条对照）：
    //   ① ES6 → ES5（async/await 用不上这里；去掉模板串、箭头函数、扩展运算符与块级声明关键字）
    //   ② 上游的 showAlert / showPrompt / showSingleSelection / showToast / notifyTaskCompletion
    //      全部没有移植 —— 我们这条链路是「拉」不是「推」，也不弹窗
    //   ③ 周次解析加固：上游的 /(\d+)-(\d+)周/ 要求「周」紧跟数字，于是
    //      「1-16(单周)」整段认不出（这门课被静默丢掉）、「1-3,5-9周」里的「1-3」也被静默丢掉。
    //      这里改成先删掉量词「周」再分段，四种写法都认（见 fixtures/weeks-forms）
    //   ④ 括号里的纯数字（"(1)"）与纯数字区间（"(1-2)"）是教学班序号，不是周次：先摘掉。
    //      只在括号外还有数字时才摘区间形态 —— 否则「(1-16周)」这种整段写在括号里的写法会被摘空
    //   ⑤ 节次解析加固：上游只认 "1-2"（split('-') 取首尾），"第9-10节" / "1,2" / 两位一拼的
    //      "0304" 会整行丢掉。这里放宽，读不出来的行计数写进 warnings（不静默丢课）
    //   ⑥ 上游要求 kcmc / xm / cdmc / xqj / jcs / zcd 六个字段**全部非空**，缺一个就把整行跳过 ——
    //      空教室（线上课）与空教师都会因此丢课。这里只要求课名 / 星期 / 节次 / 周次，
    //      教师与教室没有就留空（手册 §4.7：写「未知」会被课表当成真名显示），
    //      真正丢掉的行走分类计数写进 warnings
    //   ⑦ 开学日：上游取教务的周次校历（cxZcByXnxq），拿不到就交 null（我们的 firstDay 必填）。
    //      这里拿不到就按学期序号推算，并且**推算值一定出现在 warnings 里**（手册 §4.2）
    //   ⑧ 总周数：上游写死 20。这里优先用周次校历给出的周数，回落时仍用上游写死的 20，
    //      但课表里出现更晚的周次时必须把总周数抬上去（否则那几周的课放不下、整包会被校验拒掉），
    //      抬了就在 warnings 里单独说
    //   ⑨ 合并的排序键：上游用 localeCompare 排中文，Rhino 与 V8 的排序结果未必一致，而我们的
    //      fixture 是**逐数组比对**的。这里换成按码位比较，并给每个排序键都补上完整的次级键
    //      （不依赖排序算法的稳定性），结果与引擎无关
    //   ⑩ 记录的合并结果语义不变（先并连堂、再并周次），但课程顺序按**首次出现的排课行**排，
    //      block 顺序按（星期、起始节、结束节、起始周、结束周、单双周、教室）排 —— 都是确定的

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = Array.isArray(raw.kbList) ? raw.kbList : [];

    // 复合键（课名 + 教师）的分隔符取 NUL。用 String.fromCharCode 取，源码里不出现控制字符、
    // 也不出现转义序列 —— 第一批有两个适配器把转义序列落成了真的 NUL 字节，文件被 grep 当二进制看。
    var SEP = String.fromCharCode(0);

    var MAX_WEEK = 30;              // 载荷校验：totalWeeks ∈ 1..30，startWeek/endWeek ∈ 1..totalWeeks
    var MAX_PERIOD = 20;            // 单日节次上限：超过它一定是脏数据
    var FALLBACK_TOTAL_WEEKS = 20;  // 上游 qdhhc.js 写死的 semesterTotalWeeks
    var MAX_WARNINGS = 20;          // 载荷校验：warnings ≤ 20 条
    var MAX_WARNING_CHARS = 200;    // 载荷校验：每条 ≤ 200 字

    // 学校作息（青岛黄海学院）：取自上游 QDHHC/qdhhc.js 里那张预设节次表（TimeSlots），原样搬过来。
    // 上游没有向教务请求作息，这张表就是脚本作者对学校的了解 —— 所以它是一个**适配器自带的值**，
    // 载荷里带出去的同时必须写进 warnings 说明它没跟教务核对过。
    var SCHOOL_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:50', end: '09:35' },
        { periodIndex: 3, start: '10:05', end: '10:50' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '11:45', end: '12:30' },
        { periodIndex: 6, start: '14:00', end: '14:45' },
        { periodIndex: 7, start: '14:50', end: '15:35' },
        { periodIndex: 8, start: '16:05', end: '16:50' },
        { periodIndex: 9, start: '16:55', end: '17:40' },
        { periodIndex: 10, start: '19:10', end: '19:55' },
        { periodIndex: 11, start: '20:00', end: '20:45' }
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

    // ---------- 小工具 ----------
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

    // 任意形态的日期 → ISO 日期。先按开头认（"2026-09-07/2026-09-13" 这种一周区间的写法要取前一半），
    // 认不到再在串里找（"第1周 2026-09-07" / "2026-09-07至2026-09-13" 这类）。
    function isoOfAny(value) {
        var s = text(value);
        var m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
        if (!m) m = /(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
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

    // "08:00" / "08:00:00" → "08:00"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒（不是跳过一节）。
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // 教务接口的返回可能是裸数组，也可能套一层信封（{rows:[]} / {code:0,datas:{...}}）。
    // extract.js 原样交出来，这里按「最像行数组的那一层」找。找不到返回空数组 —— 调用方据此
    // 回落到推算，并如实写进 warnings。
    function rowsIn(value) {
        if (Array.isArray(value)) return value;
        if (!value || typeof value !== 'object') return [];
        var keys = ['rows', 'datas', 'data', 'list', 'items'];
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

    // 确定性的比较函数：不用 localeCompare（它排中文的结果跟引擎有关，而 fixture 逐数组比对）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
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

    // 教务周次校历取不到时的开学日推算。依赖学期序号的锚点，推算结果一律写进 warnings。
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

    // ---------- 周次 ----------
    // 上游 QDHHC/qdhhc.js 的写法：
    //   "1-16周" / "1-16周(单)" / "1-16周(双)" / "2-16周" / "20周"
    //   （上游的正则 /(\d+)-(\d+)周/ 与 /^(\d+)周/ 要求「周」紧跟数字，「(单)」只能写在「周」之后）
    // 上游在这两处会静默丢数据，移植件必须都认：
    //   ① "1-16(单周)"：正则一个都不命中 → 整门课被 continue 掉（不是丢周次，是丢课）
    //   ② "1-3,5-9周"：按逗号切开后 "1-3" 没有「周」字 → 前三周静默消失
    //   ③ "(单)1-16周" 上游能认（isSingle 是整段 includes），但 "(单)" 与 "1-16周" 之间
    //      若被别的分隔符切开就认不出 —— 这里统一处理
    // 另外三个坑：
    //   ④ 括号里的纯数字 "(1)" 与纯数字区间 "(1-2)" 是教学班序号，不是周次
    //   ⑤ 「周」是量词：先把它整个删掉，免得紧跟其后的 "(单)" 被切成另一段
    //   ⑥ 空白是分段符，但破折号**旁边**的空白不是：本件原来按 /[,，、;；\/|]+|\s+/ 切段，
    //      "1 -16周(单)" 这种破折号旁带空格的写法被拆成孤立的两块 ——「只上第 1 周」
    //      而且单周标记一起丢，"1- 16周" 变成「第 1 周 + 第 16 周」。但也不能学上游同族
    //      （CFEC / wenhua_01 / zjut_01 / huel_01）先 replace(/\s+/g,'') 全剥 —— 那会把
    //      "1-3周 5-9周"（空格分隔两段周次）拼成 "1-35-9"，静默吞掉一段。这里改成：
    //      只把「空白 + 区间号 + 空白」整体归一成半角 '-'，其余空白保留给切段的 \s+ 分支。
    var droppedWeeks = 0;   // 超出 1..MAX_WEEK 被丢掉的周次个数
    var orphanMarkers = 0;  // 带单/双标记但找不到任何周次段的段数
    var junkSegments = 0;   // 周次段里认不出的说明（「前」「后」「实践」之类）

    function weeksOf(source) {
        var cleaned = text(source);
        if (!cleaned) return [];
        // ① 区间号归一，连同**两侧的空白**一起：全角波浪「～」、全角连字符「－」、减号「−」、
        //    连字号「–」「—」、ASCII「~」以及中文「至/到」都当区间号使，统一成半角 '-'。
        //    破折号旁的空格在这里一起吃掉，"1 -16周(单)" 才不会裂成两段
        cleaned = cleaned.replace(/\s*[-—–−－~～至到]\s*/g, '-');
        // ② 「周」是量词：整个删掉，免得紧跟其后的 "(单)" 被切成另一段
        cleaned = cleaned.replace(/周/g, '');

        // 括号里的纯数字 / 纯数字区间是教学班序号，先摘掉。
        // 「(1-2)1-16周」这种序号在前、周次在后的写法，靠「括号外还有没有数字」来分辨；
        // 括号外一个数字都没有时（"(1-16周)" 这种整段写在括号里的写法）不摘区间形态。
        var outside = cleaned.replace(/[(（][^)）]*[)）]/g, '');
        if (/[0-9]/.test(outside)) {
            cleaned = cleaned.replace(/[(（][0-9]+-[0-9]+[)）]/g, '');
        }
        cleaned = cleaned.replace(/[(（][0-9]+[)）]/g, '');
        cleaned = cleaned.replace(/[(（]/g, '').replace(/[)）]/g, '');

        // 其余的空白是**分段符**，不许剥掉：有学校用空格分隔两段周次（"1-3周 5-9周"）、
        // 或把单双标记与周次隔开（"1-16周 双"）。上游同族「先 replace(/\s+/g,'') 再分段」
        // 的写法会把 "1-3周 5-9周" 拼成 "1-35-9"、静默吞掉一段 —— 不照抄。
        var tokens = cleaned.split(/[,，、;；\/|]+|\s+/);
        var segments = [];
        var i;
        for (i = 0; i < tokens.length; i++) {
            if (tokens[i]) segments.push(tokens[i]);
        }

        // 「单」「双」自己成一段时（"1-16周,单"）并回最近的周次段，不丢
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
            if (into < 0) { orphanMarkers++; segments[i] = ''; continue; }
            segments[into] = segments[into] + segments[i];
            segments[i] = '';
        }

        var seen = {};
        var weeks = [];
        for (i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            if (!/[0-9]/.test(segment)) {
                if (segment.indexOf('第') < 0) junkSegments++;
                continue;
            }
            // 区间号上面已经统一成 '-' 了，这里只认它
            var leftover = segment.replace(/[0-9]/g, '').replace(/-/g, '')
                .replace(/单/g, '').replace(/双/g, '').replace(/第/g, '').trim();
            if (leftover) junkSegments++;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            // 「单双周」两个标记都在 = 每周都上。两边互相排除会让整段周次变空，那才是真丢数据。
            if (onlyOdd && onlyEven) { onlyOdd = false; onlyEven = false; }
            var numbers = segment.match(/[0-9]+/g) || [];
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
    // 一个上游 course 切出多段就写成多个 block，语义等价（我们的 blocks 本来就是列表）。
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

    function unionSorted(a, b) {
        var seen = {};
        var out = [];
        var i;
        for (i = 0; i < a.length; i++) {
            if (!seen[a[i]]) { seen[a[i]] = true; out.push(a[i]); }
        }
        for (i = 0; i < b.length; i++) {
            if (!seen[b[i]]) { seen[b[i]] = true; out.push(b[i]); }
        }
        out.sort(function (x, y) { return x - y; });
        return out;
    }

    // ---------- 节次 ----------
    // 上游只认 "1-2"（split('-') 取首尾），个别部署的写法会让整行丢掉。这里认：
    //   "1-2" / "3-4节" / "第9-10节" / "1,2" / 两位一拼的 "0102"（= 第 1、2 节）/ "1-2,5-6"
    // 读不出来返回 null（调用方计数进 warnings，不静默丢课）。括号里的纯数字是序号不是节次，先摘掉。
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
        return { start: start, end: end };
    }

    // ---------- 上游的 mergeAndDistinctCourses（原样移植，只把排序键换成确定的比较） ----------
    // 阶段 1：同一门课（名称 / 教师 / 地点 / 星期 / 周次全同）里，节次**连续**的并成一段
    //         （1-2 节 + 3-4 节 → 1-4 节），完全重复的行丢掉
    // 阶段 2：同一门课同一段节次里，周次**并集**（1-8 周 + 9-16 周 → 1-16 周）
    function cmpPhase1(a, b) {
        return cmpStr(a.name, b.name) || cmpStr(a.teacher, b.teacher) ||
            cmpStr(a.position, b.position) || cmpNum(a.day, b.day) ||
            cmpStr(a.weeks.join(','), b.weeks.join(',')) ||
            cmpNum(a.startSection, b.startSection) || cmpNum(a.endSection, b.endSection);
    }

    function cmpPhase2(a, b) {
        return cmpStr(a.name, b.name) || cmpStr(a.teacher, b.teacher) ||
            cmpStr(a.position, b.position) || cmpNum(a.day, b.day) ||
            cmpNum(a.startSection, b.startSection) || cmpNum(a.endSection, b.endSection) ||
            cmpStr(a.weeks.join(','));
    }

    function mergeAndDistinctCourses(courses) {
        if (!courses || courses.length <= 1) return courses ? courses.slice(0) : [];
        var list = [];
        var i;
        for (i = 0; i < courses.length; i++) {
            var c = courses[i];
            list.push({
                name: c.name,
                teacher: c.teacher,
                position: c.position,
                day: c.day,
                startSection: c.startSection,
                endSection: c.endSection,
                weeks: c.weeks.slice(0).sort(function (a, b) { return a - b; })
            });
        }

        list.sort(cmpPhase1);
        var step1 = [];
        var current = list[0];
        for (i = 1; i < list.length; i++) {
            var next = list[i];
            var isSameCourseAndWeeks =
                current.name === next.name &&
                current.teacher === next.teacher &&
                current.position === next.position &&
                current.day === next.day &&
                current.weeks.join(',') === next.weeks.join(',');
            var isContinuous = current.endSection + 1 === next.startSection;
            var isDuplicate = current.startSection === next.startSection && current.endSection === next.endSection;
            if (isSameCourseAndWeeks && isContinuous) {
                current.endSection = next.endSection;
            } else if (isSameCourseAndWeeks && isDuplicate) {
                continue;
            } else {
                step1.push(current);
                current = next;
            }
        }
        step1.push(current);

        step1.sort(cmpPhase2);
        var step2 = [];
        var cur = step1[0];
        for (i = 1; i < step1.length; i++) {
            var nxt = step1[i];
            var isSameCourseAndSection =
                cur.name === nxt.name &&
                cur.teacher === nxt.teacher &&
                cur.position === nxt.position &&
                cur.day === nxt.day &&
                cur.startSection === nxt.startSection &&
                cur.endSection === nxt.endSection;
            if (isSameCourseAndSection) {
                cur.weeks = unionSorted(cur.weeks, nxt.weeks);
            } else {
                step2.push(cur);
                cur = nxt;
            }
        }
        step2.push(cur);

        return step2;
    }

    // ---------- 逐行转换 ----------
    var parsed = [];
    var missing = { name: 0, day: 0, period: 0, week: 0 };

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

        // 教师、教室没有就让它是空的（手册 §4.7：上游写的「未知」在课表里会当成真名显示）
        parsed.push({
            name: name,
            teacher: text(row.xm),
            position: text(row.cdmc),
            day: day,
            startSection: sections.start,
            endSection: sections.end,
            weeks: weeks
        });
    }

    if (!parsed.length) {
        // 两种失败要分清楚：教务说「没课」和「给了课但我们一行都没读懂」，
        // 后者八成是接口字段变了，报「可能还没排课」会把用户和我们都带偏。
        if (rows.length > 0) {
            throw new Error(
                '教务系统给了 ' + rows.length + ' 条排课记录，但没有一条能解析成课程（缺课程名 ' +
                missing.name + ' 行、缺星期 ' + missing.day + ' 行、缺节次 ' + missing.period +
                ' 行、缺周次 ' + missing.week + ' 行）：多半是教务系统改了课表接口的字段，' +
                '请把这条消息反馈给我们'
            );
        }
        throw new Error(
            '这个学期没有解析到任何课程：可能还没排课（假期里常见），也可能登录状态已失效。' +
            '请重新登录、打开「信息查询 - 学生课表查询」确认能看到课表后再点「提取课表」'
        );
    }

    var merged = mergeAndDistinctCourses(parsed);

    // 课程顺序按**首次出现的排课行**（不依赖合并后的排序结果，方便人工核对）
    var order = [];
    var byCourse = {};
    var i2;
    for (i2 = 0; i2 < parsed.length; i2++) {
        var item = parsed[i2];
        var key = item.name + SEP + item.teacher;
        if (!byCourse[key]) {
            byCourse[key] = {
                name: item.name,
                teacher: item.teacher || null,
                note: null,
                blocks: [],
                seen: {}
            };
            order.push(key);
        }
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    for (i2 = 0; i2 < merged.length; i2++) {
        var mergedItem = merged[i2];
        var course = byCourse[mergedItem.name + SEP + mergedItem.teacher];
        if (!course) continue;
        var runs = runsOf(mergedItem.weeks);
        for (var k = 0; k < runs.length; k++) {
            var run = runs[k];
            var blockKey = mergedItem.day + '|' + mergedItem.startSection + '|' + mergedItem.endSection +
                '|' + run.start + '|' + run.end + '|' + run.weekType + '|' + mergedItem.position;
            if (course.seen[blockKey]) continue;   // 完全重复的安排：同一条只留一条
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            if (mergedItem.endSection > maxPeriod) maxPeriod = mergedItem.endSection;
            course.blocks.push({
                dayOfWeek: mergedItem.day,
                startPeriod: mergedItem.startSection,
                endPeriod: mergedItem.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: mergedItem.position || null
            });
        }
    }

    var courses = [];
    for (i2 = 0; i2 < order.length; i2++) {
        var built = byCourse[order[i2]];
        built.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location || '', b.location || '');
        });
        courses.push({ name: built.name, teacher: built.teacher, note: built.note, blocks: built.blocks });
    }

    // ---------- 学期名 / 开学日 / 总周数 ----------
    var name0 = termName();
    var todayIso = isoOfAny(data.today) || localTodayIso();

    // 周次校历：一行一周，zs / zsmc = 第几周，日期字段各部署叫法不一
    // （上游 qdhhc.js 读的是 rq 的斜杠前一半与 zcrq 里的日期）
    var calendarRows = rowsIn(data.calendar);
    var calendarWeeks = 0;
    var calendarIso = null;
    var calendarRejected = null;
    for (i2 = 0; i2 < calendarRows.length; i2++) {
        var weekRow = calendarRows[i2] || {};
        var weekNo = intOf(weekRow.zs);
        if (weekNo === null) weekNo = intOf(weekRow.zsmc);
        var weekIso = isoOfAny(weekRow.rq) || isoOfAny(weekRow.zcrq) || isoOfAny(weekRow.ksrq) ||
            isoOfAny(weekRow.zrq);
        if (weekNo === null || weekNo < 1 || !weekIso) continue;
        if (weekNo > calendarWeeks) calendarWeeks = weekNo;
        if (weekNo === 1) {
            if (plausibleStart(weekIso)) calendarIso = weekIso;
            else calendarRejected = weekIso;
        }
    }
    if (calendarWeeks > MAX_WEEK) calendarWeeks = MAX_WEEK;

    var start;
    if (calendarIso) {
        start = { iso: mondayOfIso(calendarIso), rule: '教务周次校历' };
    } else {
        start = estimateStart(todayIso);
    }

    // 总周数：上游写死 20。有周次校历就用校历的，回落时仍用上游写死的 20；
    // 但课表里更晚的周次必须放得下（否则那些 block 会越界、整包被校验拒掉），抬高时单独说明。
    var weeksSource = calendarWeeks > 0
        ? ('教务周次校历（' + calendarWeeks + ' 周）')
        : ('适配器内置的 ' + FALLBACK_TOTAL_WEEKS + ' 周（教务周次校历接口没有返回可用数据，这是上游脚本写死的值）');
    var totalWeeks = calendarWeeks > 0 ? calendarWeeks : FALLBACK_TOTAL_WEEKS;
    var raisedBySchedule = false;
    var clamped = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) { totalWeeks = MAX_WEEK; clamped = true; }
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;

    // ---------- 作息时间 ----------
    // 上游没有向教务请求作息，用的是脚本里那张 11 节预设表 —— 先逐条验时间合法性
    // （写进 periodTimes 的时间必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒），
    // 再用空课内建节次表把课表里用到的、作息表没覆盖的节次补出来。
    var periodTimes = [];
    var badSlots = 0;
    for (i2 = 0; i2 < SCHOOL_PERIOD_TIMES.length; i2++) {
        var slot = SCHOOL_PERIOD_TIMES[i2];
        var slotStart = timeOf(slot.start);
        var slotEnd = timeOf(slot.end);
        if (!slotStart || !slotEnd || minutesOf(slotStart) >= minutesOf(slotEnd)) { badSlots++; continue; }
        periodTimes.push({ periodIndex: slot.periodIndex, start: slotStart, end: slotEnd });
    }
    var tableLength = periodTimes.length;
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

    // ---------- warnings ----------
    // 顺序固定：算出来的、猜出来的、丢掉的都要说清楚。上限 20 条 / 每条 200 字（超了整个载荷会被拒），
    // 超出的在最后一条里如实说明，不静默截断。
    var allWarnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        allWarnings.push(line);
    }

    warn(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );

    if (calendarIso) {
        warn('开学日期取自教务系统的学期周次校历：第 1 周从 ' + start.iso + ' 开始，请在学期管理里核对');
    } else if (calendarRejected) {
        warn(
            '教务周次校历给出的开学日期（' + calendarRejected + '）不属于这个学期，已忽略；第 1 周按「' +
            start.rule + '」推算为 ' + start.iso + '，请在学期管理里核对成学校实际开学日'
        );
    } else {
        warn(
            '教务系统没有给出开学日期（学期周次校历接口没有返回可用数据），第 1 周按「' + start.rule +
            '」推算为 ' + start.iso + '，请在学期管理里核对成学校实际开学日'
        );
    }

    if (raisedBySchedule) {
        warn(
            '学期总周数用的是' + weeksSource + '，但课表里有第 ' + maxWeek + ' 周的课，已按 ' +
            totalWeeks + ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else {
        warn(
            '学期总周数用的是' + weeksSource +
            (clamped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
            '，如与实际不符可在学期管理里改'
        );
    }

    warn(
        '作息时间用的是适配器内置的青岛黄海学院 ' + tableLength +
        ' 节作息表（第 1 节 ' + SCHOOL_PERIOD_TIMES[0].start + '-' + SCHOOL_PERIOD_TIMES[0].end +
        '），没有向教务核对过，如与学校实际作息不符请在学期管理里改'
    );

    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) {
            notes.push(
                extendedTo > tableLength + 1
                    ? ('第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间')
                    : ('第 ' + extendedTo + ' 节按空课内建节次表补了时间')
            );
        }
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而内置作息表只到第 ' + tableLength + ' 节：' +
            notes.join('；') + '，请核对'
        );
    }

    // 取数是否取全：正方这个接口一次性返回整学期排课，本来没有分页；但响应里带了记录总数时
    // 必须对账 —— 取到的比总数少说明响应被截断/被挡了，要出声
    var reportedTotal = intOf(data.total);
    if (reportedTotal !== null && reportedTotal > rows.length) {
        warn(
            '教务系统说这个学期有 ' + reportedTotal + ' 条排课记录，实际只取到 ' + rows.length +
            ' 条，课表可能不完整，请重试或反馈'
        );
    }

    var skipped = missing.name + missing.day + missing.period + missing.week;
    if (skipped > 0) {
        var reasons = [];
        if (missing.name) reasons.push('缺课程名 ' + missing.name + ' 行');
        if (missing.day) reasons.push('缺星期 ' + missing.day + ' 行');
        if (missing.period) reasons.push('缺节次 ' + missing.period + ' 行');
        if (missing.week) reasons.push('缺周次（或周次全超出 1-' + MAX_WEEK + ' 周）' + missing.week + ' 行');
        warn(
            '有 ' + skipped + ' 行课表数据不全（' + reasons.join('、') +
            '），已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (droppedWeeks > 0) {
        warn('有 ' + droppedWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (orphanMarkers > 0) {
        warn('有 ' + orphanMarkers + ' 处「单/双」标记找不到对应的周次（教务数据写得不完整），这些标记已忽略');
    }
    if (junkSegments > 0) {
        warn('有 ' + junkSegments + ' 处周次里带了认不出的说明（例如「前」「后」「实践」），周次按其中的数字解析，请核对');
    }
    if (sparseRows > 0) {
        warn(
            '有 ' + sparseRows + ' 行课表的节次写成了多段（例如「1-2,5-6」），已按最大跨度放入' +
            '（中间几节也算上），请核对'
        );
    }
    if (badSlots > 0) {
        warn('适配器内置作息表里有 ' + badSlots + ' 节的时间不合法，已丢弃这些节次，请反馈');
    }

    var warnings = allWarnings;
    if (allWarnings.length > MAX_WARNINGS) {
        warnings = allWarnings.slice(0, MAX_WARNINGS - 1);
        warnings.push(
            '另有 ' + (allWarnings.length - MAX_WARNINGS + 1) +
            ' 条说明因为超出上限没有显示，请把这份课表反馈给我们'
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
