(function () {
    // 塔里木大学课表解析（正方新版 jwglxt 平台）—— 第二步：教务原始行 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 TARU/taru_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑）：
    //   ① 一行一条排课：星期 xqj、节次 jcs/jc、周次 zcd
    //   ② 周次文本 → 周次集合 → 极大段（移植手册 §4.1），一段 = 一条 block。
    //      塔里木大学的 zcd 与上游 taru_01.js 认得的写法对齐，但上游只认「数字必须紧挨着『周』」
    //      那一种（正则 /(\d+)-(\d+)周/ 与 /^(\d+)周/），本批检查表要求的四种写法里有两种它会整段丢掉：
    //        "1-16周(单)"    标记在「周」之后 —— 上游认（它按 includes('(单)') 判）
    //        "(单)1-16周"    标记在区间之前 —— 上游认（rangeMatch 没有锚点）
    //        "1-16(单周)"    标记在括号里、区间后面没有「周」字 —— **上游整段丢掉**
    //        "1-3,5-9周"     混排，第一段没有「周」字 —— **上游只取到 5-9 周**
    //      这里改成：先把各种破折号 / 波浪号 /「至」「到」**连同两侧的空白**归一到半角减号，
    //      再把剩下的空白当分段符折成逗号、按逗号切段，
    //      每段自己认单双（只写了「单」「双」而没有数字的那一段绑到最近的一个周次段），
    //      最后剥括号 /「周」字取数字区间（见 weeksOf）
    //   ③ 括号里的纯数字不是周次 / 节次，是教学班序号一类的标注（检查表第 2 条，见 stripGroups）
    //   ④ 上游一行缺 kcmc / xm / cdmc / xqj / jcs / zcd 里任何一个就整行丢掉，且一声不吭；
    //      这里教师、教室允许为空（空着比写「未知」好），其余缺什么记什么、进 warnings
    //   ⑤ 节次时间的节号写法（"1-2" / "3-4节" / "0102"）与作息表完整性判断见 sectionsOf / readTimes
    //   ⑥ 开学日期：正方课表接口不给，按学年学期推算并如实说明（手册 §4.2 / §4.3）
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = raw.kbList instanceof Array ? raw.kbList : [];
    var slots = data.timeSlots instanceof Array ? data.timeSlots : null;

    var MAX_WEEK = 30;      // 载荷校验：totalWeeks ∈ 1..30，周次也按这个上限兜
    var MAX_PERIOD = 30;    // 节次号的上限：超出这个数一定是字段读错了，不是真有 31 节课
    var ASSUMED_WEEKS = 20; // 教务不给总周数时的学期长度（多数高校一学期 18-20 周）
    var MAX_WARNING = 200;  // 载荷校验：单条 warnings ≤200 字，超了**整包被拒**（检查表第 5 条）
    var SAMPLE_MAX = 40;    // 拼进 warnings 的教务原文样本，先截断再入数组

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

    // 教务原文（如认不出来的周次 / 节次写法）要拼进 warnings，先截断再入数组：
    // 载荷校验单条 200 字、超了整包被拒，而这类样本的长度是教务说了算的
    function clip(value, max) {
        var source = text(value);
        return source.length > max ? source.substring(0, max) + '…' : source;
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
     * 括号组分两类，界线是**括号里装的是不是「只是一个数字 / 数字区间」**：
     *   ① 整组就是一个数字或数字区间（"(1)"、"(1-2)"，半角全角都算）= 教学班序号这类标注，
     *      不是周次也不是节次，整组连内容一起丢掉（本批检查表第 2 条）。
     *      不丢的话 "(1)1-8周" 会被读成「第 1 周」（正则从左往右，先撞上括号里的那个 1），
     *      真实周次整段丢掉。
     *   ② 其余括号组只丢**括号字符**、内容留着：周次、节次整串被括号包住时（"(1-16周)"、
     *      "(1-2节)"），连内容一起删会把真实周次、节次整段删没；"1-16(单周)" 这种把「周」
     *      和单双标记写在括号里的正常形态也靠这一条活下来。
     */
    var SERIAL_PAREN = /[（(]\s*[0-9]+(\s*-\s*[0-9]+)?\s*[)）]/g;
    var BRACKET_CHAR = /[（）()]/g;

    function stripGroups(source) {
        return String(source).replace(SERIAL_PAREN, '').replace(BRACKET_CHAR, '');
    }

    /*
     * ---------- 周次 ----------
     * 周次文本 → 周次集合（去重、升序）。
     * 归一与切段分**两步**（顺序不能反）：
     * ① 把各种破折号 / 波浪号 / 「至」「到」**连同它两侧的空白**一起归一成半角减号「-」。
     *    教务导出的 zcd 里全角波浪（"1～16周"）、全角减号（"1－16周"）、数学减号（"1−16周"）、
     *    en dash（"1–16周"）、em dash（"1—16周"）、「1至16周」「1到16周」都出现过，正则只认
     *    半角减号的话这些写法会被读成「第 1 周」，后 15 周静默丢掉（同族 xawl / gsmc / qdhhc /
     *    cqcivc 对同一批输入都给 1-16）；区间写法还可能带空格（"1 -16周(单)"、"1- 16周"），
     *    只归一符号不归一两侧空白的话，切段会把一个区间劈成两块。
     * ② **不再全局删空白**：剩下的空白在本域里也是分段符（"1-16周 双" 的标记自成一段、
     *    "1-3周 5-9周" 是两段），把空白折成逗号再按 [,，、;；] 切段。先全文删空白会把
     *    "1-3周 5-9周" 拼成 "1-35" → 1-30 全周，静默吞掉一段（本批统一口径，与 xawl 一致）。
     * 只写了「单」「双」而**段里没有数字**的那一段（"1-16周,双"）绑定到
     * **最近的一个周次段**：前面有周次段就并进前一段，前面没有就并进后面那一段
     * （"双周,1-16周"）；两头都没有周次段（整行只有「双」）时记进 warnings，不静默。
     * 单双看的是**这一段自己的原文**，不是剥完括号的文本：单双标记就写在括号里
     * （"1-16周(单)"、"1-16(单周)"）或者自己成一段（"1-16周 双"），先剥括号再找标记的话
     * 标记会跟着括号一起没，整门课退化成每周都上 —— 这正是第一批 HBMU 栽过的地方
     * （测试方案 §3.1）。只有「单」「双」同时出现（"单双周1-16"）或只说「隔周」「每周」
     * 时才判不出相位，按每周都上兜底并计数进 warnings，不静默。
     */
    var WEEK_DASH = /\s*[-～－−–—~至到]\s*/g;
    var droppedWeeks = 0;
    var oddEvenUnknown = 0;
    var oddEvenSample = '';
    var unboundMarkers = 0;
    var unboundSample = '';

    function weeksOf(source) {
        // 两步归一（见上）：① 破折号连同两侧空白 → 半角 '-'；② 剩下的空白 → 逗号（分段符）
        var whole = text(source).replace(WEEK_DASH, '-').replace(/\s+/g, ',');
        var pieces = whole.split(/[,，;；、]/);
        var hasNumber = [];
        var i;
        for (i = 0; i < pieces.length; i++) {
            hasNumber.push(!!pieces[i] && /[0-9]/.test(pieces[i]));
        }
        var segments = [];
        for (i = 0; i < pieces.length; i++) {
            if (!pieces[i]) continue;
            if (!hasNumber[i]) {
                // 段里没有数字 = 只写了「单」「双」这类标记，绑定到最近的一个周次段
                if (segments.length) {
                    segments[segments.length - 1] = segments[segments.length - 1] + pieces[i];
                    continue;
                }
                var ahead = -1;
                for (var j = i + 1; j < pieces.length; j++) {
                    if (hasNumber[j]) {
                        ahead = j;
                        break;
                    }
                }
                if (ahead < 0) {
                    unboundMarkers++;
                    if (!unboundSample) unboundSample = clip(pieces[i], SAMPLE_MAX);
                    continue;
                }
                pieces[ahead] = pieces[i] + pieces[ahead];
                continue;
            }
            segments.push(pieces[i]);
        }
        var seen = {};
        var weeks = [];
        for (i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            if (onlyOdd && onlyEven) {
                // "单双周"：两个标记同时出现 = 每周都上。不兜底的话两边互相排除，整段会变空
                onlyOdd = false;
                onlyEven = false;
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = clip(segment, SAMPLE_MAX);
            } else if (!onlyOdd && !onlyEven &&
                (segment.indexOf('隔') >= 0 || segment.indexOf('每') >= 0)) {
                // "隔周1-16" 只说隔周、没说从哪一周起，相位猜不出来，不猜
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = clip(segment, SAMPLE_MAX);
            }
            var cleaned = stripGroups(segment).replace(/周|单|双|第/g, '');
            var match = /([0-9]+)(?:-([0-9]+))?/.exec(cleaned);
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
     *   "1-2" / "3-4节" / "第3、4节"   → 取数字
     *   "0102" / "09101112"            → 两位一节（同一平台 jcdm 字段的形态）
     * 返回首节与末节（取数字的**最小值和最大值**，不是第一个和最后一个）：
     * 上游那份按 '-' 切、取首尾，遇到 "3-4节(2)" 会得到末节 NaN，整行被默默丢掉。
     */
    function sectionsOf(row) {
        var source = text(row.jcs) || text(row.jc);
        if (!source) return null;
        var cleaned = stripGroups(source).replace(/节|第/g, '');
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
     * ---------- 节次时间（作息表）----------
     * 教务的节次时间接口返回每节的起止时间。载荷要**课表用到的**每一节都有时间：
     * 连堂 3-4 节要能查到 3 和 4 两节。缺任何一节 → 整份不交（交回去应用就不会再补
     * 自己的默认表了），改用内置默认表并说明。课表没用到的节次上缺行不算缺 ——
     * 不值得因此丢掉真实的作息时间。
     * 时间格式必须自己收：载荷校验要求 HH:mm 且结束晚于开始，一条坏数据会让整次导入失败
     * （检查表第 4 条），所以格式不对的条目在这里就剔掉并计数。
     */
    function periodIndexOf(item) {
        var candidates = [item.jcdm, item.jcmc];
        for (var i = 0; i < candidates.length; i++) {
            var n = intOf(candidates[i]);
            if (n !== null && n >= 1 && n <= MAX_PERIOD) return n;
        }
        return null;
    }

    function timeOf(value) {
        var match = /^([0-9]{1,2}):([0-9]{2})(?::[0-9]{2})?$/.exec(text(value));
        if (!match) return '';
        var hour = parseInt(match[1], 10);
        var minute = parseInt(match[2], 10);
        if (hour > 23 || minute > 59) return '';
        return pad2(hour) + ':' + pad2(minute);
    }

    // 返回 { times: [...], maxGiven: 教务给到的最大节次, bad: 读不出来的条数 }
    function readTimes(source) {
        var byIndex = {};
        var indexes = [];
        var maxGiven = 0;
        var bad = 0;
        var i;
        for (i = 0; source && i < source.length; i++) {
            var item = source[i] || {};
            var index = periodIndexOf(item);
            var start = timeOf(item.qssj);
            var end = timeOf(item.jssj);
            if (index === null || !start || !end || start >= end) {
                bad++;
                continue;
            }
            if (byIndex[index]) {
                bad++;
                continue;
            }
            byIndex[index] = { periodIndex: index, start: start, end: end };
            indexes.push(index);
            if (index > maxGiven) maxGiven = index;
        }
        indexes.sort(function (a, b) { return a - b; });
        var times = [];
        for (i = 0; i < indexes.length; i++) times.push(byIndex[indexes[i]]);
        return { times: times, maxGiven: maxGiven, bad: bad };
    }

    /*
     * ---------- 学期、开学日 ----------
     * 学期序号：优先看教务给的下拉框文本（「一/二/三」），文本认不出来再看正方代号
     * （正方 jwglxt 用 3 / 12 / 16 表示第一、二、三学期；上游 taru_01.js 用的就是 3 / 12）
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

    function yearLabel() {
        var label = text(term.xnmText);
        if (/^[0-9]{4}-[0-9]{2,4}$/.test(label)) return label;
        var year = startYear();
        if (year) return year + '-' + (year + 1);
        return label || text(term.xnm);
    }

    function startYear() {
        var match = /([0-9]{4})/.exec(text(term.xnm) || text(term.xnmText));
        return match ? parseInt(match[1], 10) : 0;
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
        // today —— 在脚本里读当前时间的话，这一条分支没法写 fixture
        var parts = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/.exec(text(data.today));
        var today = parts ? new Date(Date.UTC(parseInt(parts[1], 10), parseInt(parts[2], 10) - 1, parseInt(parts[3], 10))) : new Date();
        return {
            iso: isoOf(mondayOnOrBefore(today.getUTCFullYear(), today.getUTCMonth() + 1, today.getUTCDate())),
            rule: '今天的周一',
            guessed: true
        };
    }

    /*
     * ---------- 逐行转换 ----------
     * 课名 + 教师 相同的行合成一门课（正方一门课的多个时间段是多行，不是多门课）。
     * 分组的 key 都带前缀（c:/t:），免得课名叫 "constructor"、"__proto__" 这类字符串
     * 撞上对象原型上的属性。
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
    var maxPeriod = 0;
    var usedPeriod = {};

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
                if (!badSectionSample) badSectionSample = clip(rawSection, SAMPLE_MAX);
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
            if (range.end > maxPeriod) maxPeriod = range.end;
            for (var u = range.start; u <= range.end; u++) usedPeriod[u] = true;
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
            '请重新登录、打开「信息查询 - 学生课表查询」确认能看到课表后再试'
        );
    }

    /*
     * ---------- 总周数 ----------
     * 教务不给学期总周数（上游那份也只在本地取「课表里最大的周次」）。这里按
     * ASSUMED_WEEKS 估，被课表里更晚的周次抬高时以课表为准，两种情况都写进 warnings：
     * 总周数在库里和真值长得一模一样，只有说出来用户才有机会发现。
     */
    var totalWeeks = maxWeek > ASSUMED_WEEKS ? maxWeek : ASSUMED_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    /*
     * ---------- 节次时间 ----------
     * 课表里**用到的**每一节都要有时间：连堂 3-4 节就得能查到 3 和 4 两节。
     * 缺任何一节 → 整份不交（交回去应用就不会再补自己的默认表了），改用内置默认表并说明。
     * 只按「用到哪几节」要求，不要求从第 1 节连续排到最后一节。
     */
    var read = readTimes(slots);
    var given = {};
    var t;
    for (t = 0; t < read.times.length; t++) given[read.times[t].periodIndex] = true;
    var missing = [];
    for (t = 1; t <= maxPeriod; t++) {
        if (usedPeriod[t] && !given[t]) missing.push(t);
    }
    var complete = read.times.length > 0 && !missing.length;
    var periodTimes = complete ? read.times : [];

    var name0 = termName();
    var start = estimateStart();
    var warnings = [];

    if (start.guessed) {
        warnings.push(
            '学年读不出来，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    } else {
        warnings.push(
            '正方课表接口不给开学日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    }
    warnings.push(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );
    if (maxWeek > ASSUMED_WEEKS) {
        warnings.push(
            '课表里最晚排到第 ' + maxWeek + ' 周，超出按 ' + ASSUMED_WEEKS +
            ' 周估算的学期长度，学期总周数已按 ' + totalWeeks + ' 周导入，请核对'
        );
    } else {
        warnings.push(
            '教务没有给出学期总周数，已按 ' + ASSUMED_WEEKS + ' 周导入（课表里最晚到第 ' + maxWeek +
            ' 周），如与实际不符可在学期管理里改'
        );
    }
    if (!slots || !slots.length) {
        warnings.push(
            '没取到教务的节次时间（节次时间接口没返回数据），节次时间已改用空课内置默认表：' +
            '塔里木大学分夏季 / 非夏季两套作息，请在学期管理里核对成你实际用的那一套'
        );
    } else if (!read.times.length) {
        warnings.push(
            '教务返回了 ' + slots.length + ' 条节次时间，但没有一条能用（时间格式不对），' +
            '节次时间已改用空课内置默认表，请在学期管理里核对作息'
        );
    } else if (missing.length) {
        warnings.push(
            '教务给的节次时间不完整（最多到第 ' + read.maxGiven + ' 节，课表里用到第 ' + missing.join('、') +
            ' 节），为避免真实作息和内置默认表混用，节次时间已整份改用空课内置默认表，请核对作息'
        );
    } else if (read.bad) {
        warnings.push('教务给的节次时间里有 ' + read.bad + ' 条读不出来（时间格式不对或重复），已忽略');
    }
    var reportedTotal = intOf(data.reportedTotal);
    if (reportedTotal !== null && reportedTotal > rows.length) {
        warnings.push(
            '教务系统报了 ' + reportedTotal + ' 条排课记录，只取到 ' + rows.length +
            ' 条，课表可能不完整，请核对课程数量'
        );
    }

    var skipped = skippedName + skippedDay + skippedPeriod + skippedWeek;
    if (skipped > 0) {
        warnings.push(
            '有 ' + skipped + ' 行课表数据缺课程名 / 星期 / 节次 / 周次，已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (unreadableSection > 0 && badSectionSample) {
        warnings.push(
            '有 ' + unreadableSection + ' 行的节次写法认不出来（如「' + badSectionSample +
            '」），已跳过：如发现少课请反馈'
        );
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 处周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (oddEvenUnknown > 0) {
        warnings.push(
            '有 ' + oddEvenUnknown + ' 行的周次写法认不出单双（如「' + oddEvenSample +
            '」），已按每周都上导入，请核对'
        );
    }
    if (unboundMarkers > 0) {
        warnings.push(
            '有 ' + unboundMarkers + ' 处周次只写了单双标记、没有周次范围（如「' + unboundSample +
            '」），这些行认不出周次已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }

    // 条数上限（20 条）由上面的分支结构保证：最多 10 条。长度上限自己兜一道 ——
    // 载荷校验单条超 200 字会让**整包**被拒，而节次时间那条会随缺失节号列表变长
    for (var wi = 0; wi < warnings.length; wi++) {
        if (warnings[wi].length > MAX_WARNING) {
            warnings[wi] = warnings[wi].substring(0, MAX_WARNING - 1) + '…';
        }
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
