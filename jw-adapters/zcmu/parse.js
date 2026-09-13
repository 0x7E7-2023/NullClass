(function () {
    // 浙江中医药大学课表解析（正方新版 jwglxt 平台）
    // 移植自 shiguang_warehouse 的 ZCMU/zcmu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Daoguan-king）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游抬头自称「基于正方教务系统 v9.0 接口适配」，但它请求的是 jwglxt 新版接口
    // （/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151），平台按接口路径判、不按抬头判。
    //
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑）：
    //   ① 一行一条排课：星期 xqj、节次 jcs、周次 zcd
    //      （"1-16周" / "1-16周(单)" / "(单)1-16周" / "1-16(单周)" / "1-3,5-9周" / "2-16周(双)"
    //        / "1-16周,双" 标记自成一段 / "1～16周" 破折号异体，详见下面「周次」一节）
    //   ② 周次文本 → 周次集合 → 极大段（移植手册 §4.1），一段 = 一条 block
    //   ③ 同一门课（课名 + 教师）的多行合并成一门课的多个 block，完全重复的行去掉
    //   ④ 作息时间用上游脚本里写死的两张校区表（滨文 / 富春），校区由 extract.js 问用户；
    //      拿不到校区就按滨文导入并写进 warnings
    //   ⑤ 解析不了的行、超出 1-30 的周次、认不出单双的周次写法：跳过或兜底，全部写进 warnings
    //   ⑥ 开学日期：正方接口不给，按学年学期推算并如实说明（手册 §4.2 / §4.3）
    //   ⑦ 学期总周数：上游写死 20 周（semesterTotalWeeks），课表里排得更晚时以课表为准，
    //      两种情况都写进 warnings —— 这个值在库里和真值长得一模一样
    //
    // 与上游相比修掉的三处丢数据（都是上游静默 continue 掉的行）：
    //   · 上游要求 xm（教师）与 cdmc（教室）都非空，缺一个就整行丢掉 —— 没排教室、还没定教师的
    //     课会凭空消失。这里留空（null），行照收
    //   · 上游的周次正则要求数字后面紧跟「周」字，「1-3,5-9周」的第一段「1-3」没有周字，
    //     第 1-3 周被吞掉；「1-16(单周)」整段认不出来，这门课直接没了。见下面的周次一节
    //   · 括号里的纯数字（教学班序号）上游不剥，见下面的括号一节
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = raw.kbList instanceof Array ? raw.kbList : [];

    var MAX_WEEK = 30;      // 载荷校验：totalWeeks ∈ 1..30，周次也按这个上限兜
    var MAX_PERIOD = 30;    // 节次号上限：超出这个数一定是字段读错了，不是真有 31 节课
    var ASSUMED_WEEKS = 20; // 上游写死的学期长度（semesterTotalWeeks: 20）
    var MAX_WARNINGS = 20;      // 载荷校验：warnings ≤ 20 条
    var MAX_WARNING_TEXT = 200; // 载荷校验：每条 ≤ 200 字，超了整包被拒

    var DEFAULT_CAMPUS = '滨文校区';

    var warnings = [];

    /*
     * 核对提示统一从这里出：载荷校验对 warnings 有硬上限（≤20 条、每条 ≤200 字），
     * 超一条整包被拒（不是跳过那一条），所以长的先截断、多的直接不再收。
     */
    function pushWarning(message) {
        if (warnings.length >= MAX_WARNINGS) return;
        var line = text(message);
        if (line.length > MAX_WARNING_TEXT) line = line.substring(0, MAX_WARNING_TEXT - 1) + '…';
        warnings.push(line);
    }

    /*
     * 作息时间：上游 CampusTimeSlots 两张表逐字搬运（滨文校区 / 富春校区）。
     * 只做格式收口，数值一个字没动 —— 包括富春校区第 5 节「11:35 - 13:15」这段
     * 明显偏长的时段（疑为上游笔误）。手上有真实作息表之前不擅自改数值，
     * 改了就是把一处可疑变成一处确定的错。详见 AUDIT.md §4。
     */
    var CAMPUS_PERIODS = {
        '滨文校区': [
            { periodIndex: 1, start: '08:15', end: '08:55' },
            { periodIndex: 2, start: '09:00', end: '09:40' },
            { periodIndex: 3, start: '09:55', end: '10:35' },
            { periodIndex: 4, start: '10:40', end: '11:20' },
            { periodIndex: 5, start: '11:25', end: '12:05' },
            { periodIndex: 6, start: '13:45', end: '14:25' },
            { periodIndex: 7, start: '14:30', end: '15:10' },
            { periodIndex: 8, start: '15:20', end: '16:00' },
            { periodIndex: 9, start: '16:05', end: '16:45' },
            { periodIndex: 10, start: '16:50', end: '17:55' },
            { periodIndex: 11, start: '18:00', end: '18:40' },
            { periodIndex: 12, start: '18:45', end: '19:25' },
            { periodIndex: 13, start: '19:30', end: '20:10' }
        ],
        '富春校区': [
            { periodIndex: 1, start: '08:30', end: '09:10' },
            { periodIndex: 2, start: '09:15', end: '09:55' },
            { periodIndex: 3, start: '10:10', end: '10:50' },
            { periodIndex: 4, start: '10:55', end: '11:35' },
            { periodIndex: 5, start: '11:35', end: '13:15' },
            { periodIndex: 6, start: '13:20', end: '14:00' },
            { periodIndex: 7, start: '14:05', end: '14:45' },
            { periodIndex: 8, start: '14:55', end: '15:35' },
            { periodIndex: 9, start: '15:40', end: '16:20' },
            { periodIndex: 10, start: '16:25', end: '17:05' },
            { periodIndex: 11, start: '18:00', end: '18:40' },
            { periodIndex: 12, start: '18:45', end: '19:25' },
            { periodIndex: 13, start: '19:30', end: '20:10' }
        ]
    };
    var CAMPUS_NAMES = ['滨文校区', '富春校区'];

    var KIND_CN = { first: '一', second: '二', third: '三' };
    // 开学日的推算锚点：正方不给开学日，只能按「学期 + 学年」定到某一天所在周的周一
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

    /*
     * ---------- 括号 ----------
     * 正方把周次、节次的**标注**也写在括号里，括号因此有两类，界线是
     * 「括号里装的是不是一个纯数字 / 纯数字区间」：
     *   ① 「(1)」「(1-2)」（半角全角都算）是教学班序号这类标注，不是周次也不是节次 ——
     *      整组连内容一起丢掉。不丢的话「(1-2)1-16周」会被读成第 1-2 周（正则从左往右，
     *      先撞上括号里的 1），真实周次整段丢掉；「3-4节(1)」还会把末节压到 1、
     *      末节比首节小，整行被默默丢掉。
     *   ② 其余括号组只丢**括号字符**、内容留着：「1-16(单周)」的单双标记写在括号里，
     *      「(1-16周)」「(1-2节)」整串被括号包住，连内容一起删会把真实周次、节次删没。
     */
    var SERIAL_PAREN = /[（(]\s*[0-9]+(\s*-\s*[0-9]+)?\s*[)）]/g;
    var BRACKET_CHAR = /[（）()]/g;

    function stripSerialGroups(source) {
        return String(source).replace(SERIAL_PAREN, '');
    }

    /*
     * ---------- 周次 ----------
     * 周次文本 → 周次集合（去重、升序）。
     * 归一与切段分**两步**（顺序不能反）：
     * ① 把破折号异体（～ － − – — ~ 至 到）**连同它两侧的空白**统一成半角「-」。
     *    只认半角「-」的话，「1～16周」会被读成第 1 周 —— 后 15 周静默丢掉且一条告警都不出
     *    （同族另外几所学校是同一处漏判）；区间写法还可能带空格（"1 -16周(单)"、"1- 16周"），
     *    只归一符号不归一两侧空白的话，切段会把一个区间劈成两块。
     * ② **不再全局删空白**：剩下的空白在本域里也是分段符（"1-16周 双" 的标记自成一段、
     *    "1-3周 5-9周" 是两段），把空白折成逗号再按 [,，、;；] 切段。先全文删空白会把
     *    "1-3周 5-9周" 拼成 "1-35" → 1-30 全周，静默吞掉一段（本批统一口径，与 xawl 一致）。
     *    标准写法「1-16周」的路径与结果一个字节都不变。
     * 「单」「双」看的是**这一段自己的原文**（含分段归一，不含剥括号），不是剥完括号的文本：
     * 正方的单双周写在「周」字之后（"1-16周(单)"），先剥括号再找标记，标记会跟着括号一起没，
     * 整门课退化成每周都上 —— 这正是别的学校踩过的坑。标记写在「周」字前面（"(单)1-16周"）
     * 或者括号里（"1-16(单周)"）同样要认。
     * 标记还能**自成一段**（"1-16周,双"）：这种段里一个数字都没有，要并到**最近的周次段**上 ——
     * 前面有周次段就并进前一段，前面没有就并进后一段；两头都没有（整串里只有标记）进 warnings，
     * 不静默。不并的话它会被当成「没有数字的段」直接跳过，"1-16周,双" 会退化成每周都上。
     * 数字不再要求后面跟「周」字：混排写法「1-3,5-9周」的第一段没有周字，要求有周字会把
     * 第 1-3 周整段吞掉（上游就是这么吞的）。
     * 认不出单双的写法（「隔周1-16」「单双周1-16」）按每周都上兜底，并计数进 warnings。
     */
    var WEEK_DASH = /\s*[-～－−–—~至到]\s*/g;
    var WEEK_NOISE = /周|单|双/g;

    var droppedWeeks = 0;
    var oddEvenUnknown = 0;
    var oddEvenSample = '';

    function weeksOf(source) {
        // 两步归一（见上）：① 破折号连同两侧空白 → 半角 '-'；② 剩下的空白 → 逗号（分段符）
        var whole = text(source).replace(WEEK_DASH, '-').replace(/\s+/g, ',');
        var seen = {};
        var weeks = [];
        var segments = whole.split(/[,，;；、]/);
        var numeric = [];
        var ranges = [];
        var hasOdd = [];
        var hasEven = [];
        var vague = [];
        var onlyOddOf = [];
        var onlyEvenOf = [];
        var i;
        var j;
        var cleaned;
        var found;

        // ① 每一段先算两件事：剥掉括号里的教学班序号之后还剩不剩数字（决定它是不是周次段）、
        //    段里有没有单双标记。先剥序号再判断，「(1),双,1-16周」的「双」才不会并到只有序号
        //    的那一段上去 —— 并错了这一段就一个周次也产不出来，等于把标记丢了
        for (i = 0; i < segments.length; i++) {
            cleaned = stripSerialGroups(segments[i]).replace(BRACKET_CHAR, '').replace(WEEK_NOISE, '');
            found = /([0-9]+)(?:-([0-9]+))?/.exec(cleaned);
            ranges[i] = found;
            numeric[i] = found !== null;
            hasOdd[i] = segments[i].indexOf('单') >= 0;
            hasEven[i] = segments[i].indexOf('双') >= 0;
            vague[i] = !hasOdd[i] && !hasEven[i] &&
                (segments[i].indexOf('隔') >= 0 || segments[i].indexOf('每') >= 0);
            onlyOddOf[i] = hasOdd[i];
            onlyEvenOf[i] = hasEven[i];
        }

        // ② 自成一段的单双标记并到最近的周次段上：先往回找，找不到再往前找；两头都没有就写进
        //    warnings（归一后整串只剩标记的极脏数据），不许静默
        for (i = 0; i < segments.length; i++) {
            if (numeric[i] || (!hasOdd[i] && !hasEven[i])) continue;
            var target = -1;
            for (j = i - 1; j >= 0; j--) {
                if (numeric[j]) { target = j; break; }
            }
            if (target < 0) {
                for (j = i + 1; j < segments.length; j++) {
                    if (numeric[j]) { target = j; break; }
                }
            }
            if (target < 0) {
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = segments[i];
                continue;
            }
            if (hasOdd[i]) onlyOddOf[target] = true;
            if (hasEven[i]) onlyEvenOf[target] = true;
        }

        for (i = 0; i < segments.length; i++) {
            if (!numeric[i]) continue;
            var segment = segments[i];
            var onlyOdd = onlyOddOf[i];
            var onlyEven = onlyEvenOf[i];
            if (onlyOdd && onlyEven) {
                // 「单双周」：两个标记同时出现 = 每周都上。两边互相排除的话整段会变空
                onlyOdd = false;
                onlyEven = false;
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = segment;
            } else if (vague[i]) {
                // 「隔周1-16」只说隔周、没说从哪一周起，相位猜不出来，不猜
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = segment;
            }
            var match = ranges[i];
            if (!match) continue;
            var start = parseInt(match[1], 10);
            var end = match[2] ? parseInt(match[2], 10) : start;
            if (isNaN(start) || isNaN(end)) continue;
            if (end < start) {
                var swap = start;
                start = end;
                end = swap;
            }
            for (var week = start; week <= end; week++) {
                if (onlyOdd && week % 2 === 0) continue;
                if (onlyEven && week % 2 === 1) continue;
                if (week < 1 || week > MAX_WEEK) {
                    droppedWeeks++;
                    continue;
                }
                if (!seen[week]) {
                    seen[week] = true;
                    weeks.push(week);
                }
            }
        }
        weeks.sort(function (a, b) { return a - b; });
        return weeks;
    }

    // 周次集合 → 极大段：步长 1 视作每周，步长 2 视作单周 / 双周（手册 §4.1）
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

    /*
     * ---------- 节次 ----------
     * 节次写在 jcs（正方新版），少数部署写在 jc。三种写法都认：
     *   "1-2" / "3-4节" / "第3、4节"    区间
     *   "0102" / "09101112"            两位一节（正方 jcdm 字段的形态）
     * 取数字的**最小值与最大值**（不是第一个和最后一个）：上游取首尾，遇到 "3-4节(1)"
     * 会得到 3 到 1、末节比首节还小，整行被默默丢掉。
     */
    function sectionsOf(row) {
        var source = text(row.jcs) || text(row.jc);
        if (!source) return null;
        var cleaned = stripSerialGroups(source).replace(BRACKET_CHAR, '').replace(/节|第/g, '');
        var numbers = [];
        var i;
        var packed = cleaned.replace(/\s+/g, '');
        if (/^[0-9]+$/.test(packed) && packed.length > 2 && packed.length % 2 === 0) {
            for (i = 0; i < packed.length; i += 2) numbers.push(parseInt(packed.substr(i, 2), 10));
        } else {
            var found = cleaned.match(/[0-9]+/g) || [];
            for (i = 0; i < found.length; i++) numbers.push(parseInt(found[i], 10));
        }
        var start = null;
        var end = null;
        for (i = 0; i < numbers.length; i++) {
            var n = numbers[i];
            if (isNaN(n) || n < 1 || n > MAX_PERIOD) continue;
            if (start === null || n < start) start = n;
            if (end === null || n > end) end = n;
        }
        if (start === null) return null;
        return { start: start, end: end, raw: source };
    }

    /*
     * ---------- 学期、开学日 ----------
     * 学期序号：优先看教务给的下拉框文本（「一 / 二 / 三」），文本认不出来再看正方代号
     * —— 上游 getSemesterCode 用的就是 3 = 第一学期、12 = 第二学期（16 = 第三学期是正方通用约定）
     */
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

    function startYear() {
        var match = /([0-9]{4})/.exec(text(term.xnm) || text(term.xnmText));
        return match ? parseInt(match[1], 10) : 0;
    }

    function yearLabel() {
        var label = text(term.xnmText);
        if (/^[0-9]{4}-[0-9]{2,4}$/.test(label)) return label;
        var year = startYear();
        if (year) return year + '-' + (year + 1);
        return label || text(term.xnm);
    }

    function termName() {
        var explicit = text(term.name);
        if (explicit) return explicit;
        var whole = text(term.xqmText);
        // 有的学校把整个「2026-2027学年第一学期」放进学期下拉框
        if (whole && whole.indexOf('学年') >= 0 && whole.indexOf('学期') >= 0) return whole;
        var label = yearLabel();
        var suffix = '第' + KIND_CN[termKind()] + '学期';
        return label ? label + '学年' + suffix : suffix;
    }

    function estimateStart() {
        var kind = termKind();
        var anchor = KIND_ANCHOR[kind];
        var year = startYear();
        if (year) {
            if (kind === 'second' || kind === 'third') year = year + 1;
            return { iso: isoOf(mondayOnOrBefore(year, anchor.month, anchor.day)), rule: anchor.rule, guessed: false };
        }
        // 学年读不出来（课表页被改过）时退回「今天所在周的周一」。日期取 extract 交过来的
        // today —— 在脚本里读当前时间的话，这条分支没法写 fixture
        var parts = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/.exec(text(data.today));
        var today = parts
            ? new Date(Date.UTC(parseInt(parts[1], 10), parseInt(parts[2], 10) - 1, parseInt(parts[3], 10)))
            : new Date();
        return {
            iso: isoOf(mondayOnOrBefore(today.getUTCFullYear(), today.getUTCMonth() + 1, today.getUTCDate())),
            rule: '今天的周一',
            guessed: true
        };
    }

    /*
     * ---------- 校区 → 作息表 ----------
     * 两个校区的上课时间不同，页面上没有这个信息，只能由 extract.js 问用户。
     * 拿不到（桥不可用 / 用户取消）或者认不出来，就按滨文校区导入 —— 但必须说出来。
     */
    var campus = text(data.campus);
    var campusKnown = false;
    for (var cn = 0; cn < CAMPUS_NAMES.length; cn++) {
        if (CAMPUS_NAMES[cn] === campus) campusKnown = true;
    }
    var campusFellBack = !campusKnown;
    if (campusFellBack) {
        if (campus) {
            pushWarning('校区「' + campus + '」认不出来，节次时间按' + DEFAULT_CAMPUS +
                '导入；如果你不在' + DEFAULT_CAMPUS + '，请在学期管理里核对作息时间');
        } else {
            pushWarning('没拿到校区选择（提问桥不可用，或用户取消了），节次时间按' + DEFAULT_CAMPUS +
                '导入；如果你不在' + DEFAULT_CAMPUS + '，请在学期管理里核对作息时间');
        }
        campus = DEFAULT_CAMPUS;
    }
    var periodTimes = CAMPUS_PERIODS[campus];

    /*
     * ---------- 逐行转换 ----------
     * 课名 + 教师相同的行合成一门课（正方一门课的多个时间段是多行，不是多门课）。
     * 分组的 key 都带前缀（c: / t:），免得课名叫 "constructor"、"__proto__" 这类字符串
     * 撞上对象原型上的属性 —— 也就不用往 key 里塞 NUL 这类分隔符。
     */
    var order = [];
    var byName = {};
    var skippedName = 0;
    var skippedDay = 0;
    var skippedPeriod = 0;
    var skippedWeek = 0;
    var unreadableSection = 0;
    var badSectionSample = '';
    var maxWeek = 0;

    for (var r = 0; r < rows.length; r++) {
        var row = rows[r] || {};
        var name = text(row.kcmc);
        if (!name) {
            skippedName++;
            continue;
        }
        var day = intOf(row.xqj);
        if (!(day >= 1 && day <= 7)) {
            skippedDay++;
            continue;
        }
        var range = sectionsOf(row);
        if (!range) {
            skippedPeriod++;
            var rawSection = text(row.jcs) || text(row.jc);
            if (rawSection) {
                unreadableSection++;
                if (!badSectionSample) badSectionSample = rawSection;
            }
            continue;
        }
        var weeks = weeksOf(row.zcd);
        if (!weeks.length) {
            skippedWeek++;
            continue;
        }

        // 教师、教室没有就让它是空的（上游写的「未知」「未排地点」在课表里会被当成真名显示）
        var teacher = text(row.xm) || null;
        var location = text(row.cdmc) || text(row.cdbh) || null;
        var nameKey = 'c:' + name;
        var bucket = byName[nameKey];
        if (!bucket) {
            bucket = { teachers: {}, list: [] };
            byName[nameKey] = bucket;
            order.push(bucket);
        }
        var teacherKey = 't:' + (teacher === null ? '' : teacher);
        var course = bucket.teachers[teacherKey];
        if (!course) {
            course = { name: name, teacher: teacher, note: null, blocks: [], seen: {} };
            bucket.teachers[teacherKey] = course;
            bucket.list.push(course);
        }

        var runs = runsOf(weeks);
        for (var k = 0; k < runs.length; k++) {
            var run = runs[k];
            var blockKey = day + '|' + range.start + '|' + range.end + '|' + run.start + '|' +
                run.end + '|' + run.weekType + '|' + (location === null ? '' : location);
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            course.blocks.push({
                dayOfWeek: day,
                startPeriod: range.start,
                endPeriod: range.end,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: location
            });
        }
    }

    var courses = [];
    for (var b = 0; b < order.length; b++) {
        var list = order[b].list;
        for (var c = 0; c < list.length; c++) {
            courses.push({
                name: list[c].name,
                teacher: list[c].teacher,
                note: list[c].note,
                blocks: list[c].blocks
            });
        }
    }

    if (!courses.length) {
        throw new Error(
            '这个学期没有解析到任何课程：可能还没排课，也可能登录状态已失效。' +
            '请重新登录、打开「信息查询 - 个人课表查询」确认能看到课表后再试'
        );
    }

    /*
     * ---------- 总周数 ----------
     * 正方不给校历周数，上游直接写死 20 周。这里同样从 20 起，但课表里排得更晚时以课表为准，
     * 并 clamp 到 1..30（载荷校验的上限），两种情况都写进 warnings。
     */
    var totalWeeks = maxWeek > ASSUMED_WEEKS ? maxWeek : ASSUMED_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;
    if (!(totalWeeks >= 1)) totalWeeks = ASSUMED_WEEKS;

    var name0 = termName();
    var start = estimateStart();

    if (start.guessed) {
        pushWarning(
            '学年读不出来，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    } else {
        pushWarning(
            '正方课表接口不给开学日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    }
    pushWarning(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );
    if (maxWeek > ASSUMED_WEEKS) {
        pushWarning(
            '课表里最晚排到第 ' + maxWeek + ' 周，超出按 ' + ASSUMED_WEEKS +
            ' 周估算的学期长度，学期总周数已按 ' + totalWeeks + ' 周导入，请核对'
        );
    } else {
        pushWarning(
            '教务没有给出学期总周数，已按 ' + ASSUMED_WEEKS + ' 周导入（课表里最晚到第 ' + maxWeek +
            ' 周），如与实际不符可在学期管理里改'
        );
    }

    var skipped = skippedName + skippedDay + skippedPeriod + skippedWeek;
    if (skipped > 0) {
        pushWarning(
            '有 ' + skipped + ' 行课表数据缺课程名 / 星期 / 节次 / 周次，已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (unreadableSection > 0 && badSectionSample) {
        pushWarning(
            '有 ' + unreadableSection + ' 行的节次写法认不出来（如「' + badSectionSample +
            '」），已跳过：如发现少课请反馈'
        );
    }
    if (droppedWeeks > 0) {
        pushWarning('有 ' + droppedWeeks + ' 处周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (oddEvenUnknown > 0) {
        pushWarning(
            '有 ' + oddEvenUnknown + ' 行的周次写法认不出单双（如「' + oddEvenSample +
            '」），已按每周都上导入，请核对'
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
