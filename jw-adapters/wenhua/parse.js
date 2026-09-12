(function () {
    // 文华学院课表解析（正方新版 jwglxt 平台）—— 第二步：教务原始行 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 WENHUA/wenhua_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 glxgo）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游与同平台的 ZJUT/zjut_01.js 是近克隆，两份 diff 只有四处：
    //   ① 菜单号 gnmmkdm=N2151（zjut 是 N253508）② 多一个节次时间接口（本文件的 ④）
    //   ③ xqh_id 默认值 ④ 节次表来源（上游这里改成向教务要，zjut 那份写死）
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑）：
    //   ① 一行一条排课：星期 xqj、节次 jcs/jc、周次 zcd
    //      （"1-16周" / "1-16周(单)" / "1-16周(双)" / "1-4,6-8周" / "双周1-16" /
    //        整串被括号包住的 "(1-16周)"、"1-16(周)"、"1-16周 双"）
    //   ② 周次文本 → 周次集合 → 极大段（移植手册 §4.1），一段 = 一条 block
    //   ③ 同一门课（课名 + 教师）的多行合并成一门课的多个 block，完全重复的行去掉
    //   ④ 节次时间用教务自己的作息接口（上游第 3 个请求，zjut 那份是写死的）；
    //      它覆盖不到课表用到的每一节时**整份不用**，回落应用内置默认节次表并说明 ——
    //      真实作息与内置表混拼出来的表比哪一份单独用都糟，且用户看不出来
    //   ⑤ 解析不了的行、超出 1-30 的周次、认不出单双的周次写法：跳过或兜底，全部写进 warnings
    //   ⑥ 开学日期：正方接口不给，按学年学期推算并如实说明（手册 §4.2/§4.3）
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = raw.kbList instanceof Array ? raw.kbList : [];
    var slots = data.timeSlots instanceof Array ? data.timeSlots : null;

    var MAX_WEEK = 30;      // 载荷校验：totalWeeks ∈ 1..30，周次也按这个上限兜
    var MAX_PERIOD = 30;    // 节次号的上限：超出这个数一定是字段读错了，不是真有 31 节课
    var ASSUMED_WEEKS = 20; // 教务不给总周数时的学期长度（多数高校一学期 18-20 周）

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
     * 括号组分两类，界线是**括号里装的是不是「只是一个数字 / 数字区间」**：
     *   ① 整组就是一个数字或数字区间（"(1)"、"(1-2)"，半角全角都算）= 教学班序号这类标注，
     *      不是周次也不是节次，整组连内容一起丢掉。
     *      不丢的话 "(1)1-16周" 会被读成「第 1 周」（正则从左往右，先撞上括号里的那个 1），
     *      真实周次整段丢掉；"3-4节(1)" 还会把末节压到 1。
     *   ② 其余括号组只丢**括号字符**、内容留着（上游 wenhua_01.js 就是这么做的——
     *      它按 周 / 单 / 双 / 左括号 / 右括号 逐个删）。周次、节次整串被括号包住时
     *      （"(1-16周)"、"(1-2节)"），连内容一起删会把真实周次、节次整段删没：单行时
     *      整份抛「没有解析到任何课程」，夹在多行里时那门课**凭空消失**，而跳过的理由
     *      还会写成「缺课程名 / 星期 / 节次 / 周次」——四个字段其实都在。
     *      "1-16(周)" 这种把「周」写进括号里的正常形态也靠这一条活下来。
     */
    var SERIAL_PAREN = /[（(]\s*[0-9]+(\s*-\s*[0-9]+)?\s*[)）]/g;
    var BRACKET_CHAR = /[（）()]/g;

    function stripGroups(source) {
        return String(source).replace(SERIAL_PAREN, '').replace(BRACKET_CHAR, '');
    }

    /*
     * ---------- 周次 ----------
     * 周次文本 → 周次集合（去重、升序）。
     * 「单」「双」看的是**这一段自己的原文**（先抹掉空白，再找标记），不是剥完括号的文本：
     * 正方的单双周写在「周」字之后（"1-16周(双)"），先剥括号再找标记，标记会跟着括号一起没，
     * 整门课退化成每周都上；被空白隔开、自己成一段的标记（"1-16周 双"）同理，不能丢。
     * 认不出单双的写法（"隔周1-16"、"单双周1-16"）按每周都上兜底，并计数进 warnings。
     */
    var droppedWeeks = 0;
    var oddEvenUnknown = 0;
    var oddEvenSample = '';

    function weeksOf(source) {
        var whole = text(source).replace(/\s+/g, '');
        var seen = {};
        var weeks = [];
        var segments = whole.split(/[,，;；、]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment || !/[0-9]/.test(segment)) continue;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            if (onlyOdd && onlyEven) {
                // "单双周"：两个标记同时出现 = 每周都上。不兜底的话两边互相排除，整段会变空
                onlyOdd = false;
                onlyEven = false;
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = segment;
            } else if (!onlyOdd && !onlyEven &&
                (segment.indexOf('隔') >= 0 || segment.indexOf('每') >= 0)) {
                // "隔周1-16" 只说隔周、没说从哪一周起，相位猜不出来，不猜
                oddEvenUnknown++;
                if (!oddEvenSample) oddEvenSample = segment;
            }
            var cleaned = stripGroups(segment).replace(/周|单|双/g, '');
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
     *   "1-2" / "3-4节" / "第3、4节"   → 区间
     *   "0102" / "09101112"            → 两位一节（同一平台 jcdm 字段的形态）
     * 返回首节与末节（取数字的**最小值和最大值**，不是第一个和最后一个）：
     * 上游那份取首尾，遇到 "3-4节(1)" 会得到 3→1、末节比首节还小，整行被默默丢掉。
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
     * 教务的节次时间接口（上游第 3 个请求）返回每节的起止时间。
     * 载荷要**课表用到的**每一节都有时间：连堂 3-4 节要能查到 3 和 4 两节。缺任何一节 →
     * 整份不交（交回去应用就不会再补自己的默认表了），改用内置默认表并说明。
     * 课表没用到的节次上缺行不算缺 —— 不值得因此丢掉真实的作息时间。
     * 时间格式必须自己收：载荷校验要求 HH:mm 且结束晚于开始，一条坏数据会让整次导入失败。
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
     * （正方 jwglxt 用 3 / 12 / 16 表示第一、二、三学期）
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
     * 只按「用到哪几节」要求，不要求从第 1 节连续排到最后一节 —— 教务给的表在课表没用到的
     * 位置上缺几行（例如晚上 9-12 节单独一张表）是它的事，不值得因此丢掉真实的作息时间。
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
            '没取到教务的节次时间（节次时间接口没返回数据），节次时间已改用空课内置默认表，请在学期管理里核对作息'
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
