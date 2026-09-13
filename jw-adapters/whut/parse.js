(function () {
    // 武汉理工大学教务适配器（金智教育 jwapp / kcbcxby 模块）—— 原始数据 → 空课课表载荷
    //
    // 移植自 shiguang_warehouse 的 WHUT/whut_01.js（MIT，上游维护者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游快照：main @ e62554a（2026-09-12）
    //
    // 这一段是纯转换（不碰 DOM、不发请求），所以能在 CI 里用 Rhino 跑回归。
    // 移植过来的核心逻辑都在这里：
    //   ① SKZC「0/1 位串」→ 周次数组（上游 parseWeeksFromSkzc）；
    //   ② 节次字典 DM → 第几节（上游 fetchSectionMapping 的换算部分）；
    //   ③ 「同一门课 + 同一教师 + 同一地点 + 同一天」的合并（上游 mergeContinuousLessons）：
    //      按周求节次的并集，再把每周的节次切成连续的极大块，最后按「节次块」汇总周次 ——
    //      单双周割裂、实验课节次部分重叠、重复行都靠这一步收敛。
    //
    // 移植改动（与上游的语义差异，逐条列在这里）：
    //   ① 周次：上游发显式的 weeks 数组，这里按空课载荷切成极大段 —— 步长 1 是每周（ALL），
    //      步长 2 是单/双周（ODD/EVEN），落单的一周单独成段。见移植手册 §4.1。
    //   ② 教师 / 教室：上游取 SKJS.split('/')[0]（多人只留第一个），教室拿不到时兜底写「待定」。
    //      这里教师原样保留（按「/」截断是悄悄丢人），拿不到就留 null；教室同样留 null
    //      —— 「待定」会被当成真教室显示。
    //   ③ 节次字典：上游只在请求抛异常时用内置对照表，接口返回空数组时会**静默丢掉全部课程**；
    //      这里「字典为空」一律走内置对照表，并把这件事写进 warnings。
    //   ④ 解析不了的排课记录：上游 filter 掉不留痕，这里分类计数写进 warnings（不许静默丢课）。
    //   ⑤ 节次越界：作息表只有 13 节，超出的并入第 13 节并进 warnings —— 越界的节次会让整个
    //      载荷被拒（不是跳过一节），位置不准也强过让用户白跑一趟。
    //   ⑥ 块的顺序 = 输入行顺序 × 周次升序（确定、可复现）。上游最后按 (星期, 起始节, 课名)
    //      排过一遍，那是展示顺序，交给应用排。
    //
    // 输入（extract.js 交出来的教务原始数据）：
    //   { term: { code, name, source, firstDay, firstDaySource, totalWeeks },
    //     sectionRows: [ { DM, MC } ],      // 节次字典原样
    //     rows:        [ 课表行 ] }
    var data = JSON.parse(typeof __ncInput !== 'undefined' ? __ncInput : '{}');
    var rows = data.rows || [];
    var sectionRows = data.sectionRows || [];
    var term = data.term || {};

    var MAX_WEEKS = 30;              // 载荷校验要求 totalWeeks ∈ 1..30（规范 §4）
    var FALLBACK_WEEKS = 20;
    var MAX_WARNINGS = 20;           // 载荷校验的硬上限（超了整个载荷被拒）
    var MAX_WARNING_TEXT = 200;

    // 本校作息时间（13 节），取自上游脚本的 presetTimeSlots
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:50', end: '09:35' },
        { periodIndex: 3, start: '09:55', end: '10:40' },
        { periodIndex: 4, start: '10:45', end: '11:30' },
        { periodIndex: 5, start: '11:35', end: '12:20' },
        { periodIndex: 6, start: '14:00', end: '14:45' },
        { periodIndex: 7, start: '14:50', end: '15:35' },
        { periodIndex: 8, start: '15:40', end: '16:25' },
        { periodIndex: 9, start: '16:45', end: '17:30' },
        { periodIndex: 10, start: '17:35', end: '18:20' },
        { periodIndex: 11, start: '19:00', end: '19:45' },
        { periodIndex: 12, start: '19:50', end: '20:35' },
        { periodIndex: 13, start: '20:40', end: '21:25' }
    ];

    // 节次字典拿不到时的内置对照表，与上游脚本里的兜底字典逐字一致（DM → 第几节）。
    // 教务的节次编码不连续：6 / 7 / 13 是午休与晚间空档，没有课。
    var FALLBACK_SECTION_MAP = {
        '1': 1, '2': 2, '3': 3, '4': 4, '5': 5,
        '8': 6, '9': 7, '10': 8, '11': 9, '12': 10,
        '14': 11, '15': 12, '16': 13
    };

    var warnings = [];
    var omittedWarnings = 0;

    function pushWarning(message) {
        var one = String(message);
        if (one.length > MAX_WARNING_TEXT) one = one.substring(0, MAX_WARNING_TEXT - 1) + '…';
        if (warnings.length >= MAX_WARNINGS - 1) { omittedWarnings++; return; }
        warnings.push(one);
    }

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

    // 'yyyy-MM-dd' → 该日期所在周的周一（含当天，当天是周一则原样返回）。
    // 上游 AppSettingsRepository 数周次前会先把开学日回退到 firstDayOfWeek（= 1，周一）那天，
    // 直接拿开学日当第 1 周会把整学期的课偏几天。见移植手册 §4.3。
    function mondayOnOrBefore(iso) {
        var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso));
        if (!m) return null;
        var year = parseInt(m[1], 10);
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        var date = new Date(Date.UTC(year, month - 1, day));
        if (isNaN(date.getTime())) return null;
        // 非法日期（如 2026-02-31）会被 Date 归一化成下个月，拒绝而不是悄悄顺延
        if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
            return null;
        }
        var offset = (date.getUTCDay() + 6) % 7;
        var monday = new Date(date.getTime() - offset * 86400000);
        return monday.getUTCFullYear() + '-' + pad2(monday.getUTCMonth() + 1) + '-' + pad2(monday.getUTCDate());
    }

    // extract 没给任何开学日时的最后兜底（本机当前周的周一）。
    // 正常路径下 extract 一定会给值（真值或推算值），所以 CI 的 fixture 不会走到这里 ——
    // 这里只是保证载荷永远合法（firstDay 是必填项）。
    function currentMondayIso() {
        var now = new Date();
        var offset = (now.getDay() + 6) % 7;
        var monday = new Date(now.getTime() - offset * 86400000);
        return monday.getFullYear() + '-' + pad2(monday.getMonth() + 1) + '-' + pad2(monday.getDate());
    }

    // SKZC 是「第 i 位 = 第 i+1 周」的位串（金智 jwapp 平台的学生课表标准编码）
    function weeksFromBits(bits) {
        var weeks = [];
        for (var i = 0; i < bits.length; i++) {
            if (bits.charAt(i) === '1') weeks.push(i + 1);
        }
        return weeks;
    }

    // 周次集合（升序去重）→ 极大段。步长 1 视作每周，步长 2 视作单/双周，落单的一周用 ALL。
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

    // 学期编号形如 2026-2027-1（教务的 XNXQDM 就是这个形状）。
    // 教务没给学期名时按编号拼一个可读名字 —— 不要拿适配器名当学期名。
    function termNameFrom(code) {
        var m = /^(\d{4})-(\d{4})-(\d{1,2})$/.exec(String(code));
        if (!m) return '';
        var index = parseInt(m[3], 10);
        var label = index === 1 ? '第一学期' : (index === 2 ? '第二学期' : (index === 3 ? '第三学期' : '第' + index + '学期'));
        return m[1] + '-' + m[2] + '学年' + label;
    }

    // ---- 节次字典：DM → 第几节 --------------------------------------------------
    // 上游口径：按接口返回的顺序，**只数名称里带「节」的行**，第 n 个这样的行就是第 n 节。
    // 所以 DM 是不连续的（6 / 7 / 13 这类空档没有「节」字样），字典给的就是真实节次号。
    function sectionMapOf(list) {
        var map = {};
        var index = 1;
        for (var i = 0; i < list.length; i++) {
            var row = list[i] || {};
            var dm = text(row.DM);
            if (!dm || text(row.MC).indexOf('节') < 0) continue;
            map[dm] = index;
            index++;
        }
        return map;
    }

    function isEmptyMap(map) {
        for (var key in map) {
            if (map.hasOwnProperty(key)) return false;
        }
        return true;
    }

    // 复合键一律用 JSON.stringify 拼数组：不用控制字符当分隔符（工具链会把转义序列落成
    // 真的 NUL 字节，文件会变成二进制），也不会因为课名里带分隔符而串键。
    function groupKeyOf(name, teacher, location, day) {
        return JSON.stringify([name, teacher, location, day]);
    }

    function courseKeyOf(name, teacher) {
        return JSON.stringify([name, teacher]);
    }

    // 把「某周里的某段连续节次」记一笔：同一个节次段的周次汇总到一起，
    // 正是上游 mergeContinuousLessons 里的 blockMap。
    function addWeek(byRange, order, startPeriod, endPeriod, week) {
        var key = startPeriod + '-' + endPeriod;
        if (!byRange[key]) {
            byRange[key] = { startPeriod: startPeriod, endPeriod: endPeriod, weeks: [] };
            order.push(key);
        }
        byRange[key].weeks.push(week);
    }

    var sectionMap = sectionMapOf(sectionRows);
    var usedFallbackMap = false;
    if (isEmptyMap(sectionMap)) {
        sectionMap = FALLBACK_SECTION_MAP;
        usedFallbackMap = true;
    }

    var groups = {};
    var groupOrder = [];
    var maxWeek = 0;
    var maxBits = 0;
    var skippedMeta = 0;      // 缺课名 / 星期不合法 / 没有周次
    var skippedSection = 0;   // 节次编码在对照表里找不到
    var badBits = 0;          // SKZC 不是 0/1 位串
    var droppedWeeks = 0;     // 超出 30 周的周次
    var clamped = 0;          // 节次超出作息表行数

    for (var i = 0; i < rows.length; i++) {
        var row = rows[i] || {};
        var name = text(row.KCM);
        var bits = text(row.SKZC);
        var isBitString = /^[01]+$/.test(bits);

        // 完全空白的占位行（课名 / 星期 / 周次都没有）不是「被丢掉的课」，不计入跳过数
        if (!name && !bits && !text(row.SKXQ)) continue;

        // 位串长度就是教务眼里的学期周数（上游口径），只在它确实是位串时才算
        if (isBitString && bits.length > maxBits) maxBits = bits.length;

        var day = intOf(row.SKXQ);
        var weeks = isBitString ? weeksFromBits(bits) : [];
        if (weeks.length) {
            var kept = [];
            for (var w = 0; w < weeks.length; w++) {
                if (weeks[w] <= MAX_WEEKS) kept.push(weeks[w]);
                else droppedWeeks++;
            }
            weeks = kept;
        }

        var startPeriod = intOf(sectionMap[text(row.KSJC)]);
        var endPeriod = intOf(sectionMap[text(row.JSJC)]);

        if (!name || !(day >= 1 && day <= 7)) { skippedMeta++; continue; }
        if (bits && !isBitString) { badBits++; continue; }
        if (!weeks.length) { skippedMeta++; continue; }
        if (!(startPeriod >= 1) || !(endPeriod >= 1) || endPeriod < startPeriod) { skippedSection++; continue; }

        if (startPeriod > PERIOD_TIMES.length || endPeriod > PERIOD_TIMES.length) {
            if (startPeriod > PERIOD_TIMES.length) startPeriod = PERIOD_TIMES.length;
            if (endPeriod > PERIOD_TIMES.length) endPeriod = PERIOD_TIMES.length;
            if (endPeriod < startPeriod) endPeriod = startPeriod;
            clamped++;
        }

        var teacher = text(row.SKJS) || null;
        var location = text(row.JASMC) || null;
        var key = groupKeyOf(name, teacher, location, day);
        var group = groups[key];
        if (!group) {
            group = { name: name, teacher: teacher, location: location, day: day, sectionsByWeek: {} };
            groups[key] = group;
            groupOrder.push(key);
        }
        for (var k = 0; k < weeks.length; k++) {
            if (weeks[k] > maxWeek) maxWeek = weeks[k];
            var bucket = group.sectionsByWeek[weeks[k]];
            if (!bucket) { bucket = {}; group.sectionsByWeek[weeks[k]] = bucket; }
            for (var s = startPeriod; s <= endPeriod; s++) bucket[s] = true;
        }
    }

    // ---- 合并：按周取节次并集 → 连续节次块 → 每个块的周次集合 ----------------------
    var courseIndex = {};
    var courseOrder = [];

    for (var gi = 0; gi < groupOrder.length; gi++) {
        var grp = groups[groupOrder[gi]];
        var blockKeys = [];
        var blocksByRange = {};

        for (var week = 1; week <= maxWeek; week++) {
            var weekSections = grp.sectionsByWeek[week];
            if (!weekSections) continue;
            var sections = [];
            for (var sec = 1; sec <= PERIOD_TIMES.length; sec++) {
                if (weekSections[sec]) sections.push(sec);
            }
            if (!sections.length) continue;

            var runStart = sections[0];
            var prev = sections[0];
            for (var si = 1; si < sections.length; si++) {
                if (sections[si] === prev + 1) { prev = sections[si]; continue; }
                addWeek(blocksByRange, blockKeys, runStart, prev, week);
                runStart = sections[si];
                prev = sections[si];
            }
            addWeek(blocksByRange, blockKeys, runStart, prev, week);
        }

        var courseKey = courseKeyOf(grp.name, grp.teacher);
        var course = courseIndex[courseKey];
        if (!course) {
            course = { name: grp.name, teacher: grp.teacher, note: null, blocks: [] };
            courseIndex[courseKey] = course;
            courseOrder.push(courseKey);
        }

        for (var bi = 0; bi < blockKeys.length; bi++) {
            var range = blocksByRange[blockKeys[bi]];
            var runs = runsOf(range.weeks);
            for (var ri = 0; ri < runs.length; ri++) {
                course.blocks.push({
                    dayOfWeek: grp.day,
                    startPeriod: range.startPeriod,
                    endPeriod: range.endPeriod,
                    startWeek: runs[ri].start,
                    endWeek: runs[ri].end,
                    weekType: runs[ri].weekType,
                    location: grp.location
                });
            }
        }
    }

    var termCode = text(term.code);

    if (courseOrder.length === 0) {
        // 有课但全被周次上限挡掉时，别把原因说成「教务改了格式」
        var hint = droppedWeeks > 0
            ? '：课表里的周次都超过了 ' + MAX_WEEKS + ' 周（课表数据的上限），放不进空课'
            : (text(term.source) === 'guess'
                ? '：学期编号是推算出来的，可能不是本学期 —— 请核对后再试'
                : '：可能是还没排课，或教务系统改了课表的数据格式。请在教务页面里确认课表已经显示出来再重试');
        throw new Error('没解析到任何课程' + (termCode ? '（学期编号 ' + termCode + '）' : '') + hint);
    }

    // ---- 学期元信息 ------------------------------------------------------------

    // 学期编号是推算出来的时要说清楚：猜错学期等于整张课表都错，而推算值和真值在库里长得一样。
    if (text(term.source) === 'guess') {
        pushWarning('学期编号 ' + termCode + ' 是按本机日期推算的（没能从教务获取），请核对导入的是不是本学期');
    }

    var firstDay = mondayOnOrBefore(term.firstDay);
    var firstDayGuessed = !firstDay || text(term.firstDaySource) === 'guess';
    if (!firstDay) firstDay = mondayOnOrBefore(currentMondayIso());
    if (firstDayGuessed) {
        pushWarning('开学日期无法从教务获取，已按最近的周一推算，请在学期管理里核对');
    }

    if (usedFallbackMap && rows.length > 0) {
        pushWarning('节次字典没能从教务获取，已按内置的节次对照表换算，请核对课表里的节次顺序');
    }

    // 课表接口回了总条数却没给全（教务某天开始分页时的症状）：说清楚，别让用户以为课就这么多
    var totalRows = intOf(data.totalSize);
    if (totalRows !== null && totalRows > rows.length) {
        pushWarning('教务只返回了 ' + rows.length + ' 条排课记录（共 ' + totalRows + ' 条），可能有课程没取到，请在教务页面核对');
    }

    var calendarWeeks = intOf(term.totalWeeks);
    if (calendarWeeks !== null && (calendarWeeks < 1 || calendarWeeks > MAX_WEEKS)) calendarWeeks = null;

    var totalWeeks = calendarWeeks;
    if (totalWeeks === null) totalWeeks = maxBits > 0 ? Math.min(maxBits, MAX_WEEKS) : maxWeek;
    if (!(totalWeeks >= 1)) totalWeeks = FALLBACK_WEEKS;
    if (totalWeeks < maxWeek) totalWeeks = maxWeek;
    if (totalWeeks > MAX_WEEKS) totalWeeks = MAX_WEEKS;

    if (calendarWeeks !== null && maxWeek > calendarWeeks) {
        pushWarning('教务给的学期总周数是 ' + calendarWeeks + ' 周，但课表里有第 ' + maxWeek +
            ' 周的课，已按 ' + totalWeeks + ' 周导入，请核对');
    }
    if (skippedMeta > 0) {
        pushWarning('有 ' + skippedMeta + ' 条排课记录缺少课名、星期或周次，没有导入');
    }
    if (skippedSection > 0) {
        pushWarning('有 ' + skippedSection + ' 条排课记录的节次编码对不上节次对照表（查不到，或结束节早于开始节），没有导入');
    }
    if (badBits > 0) {
        pushWarning('有 ' + badBits + ' 条排课记录的周次编码不是 0/1 位串（可能是教务改了格式），没有导入');
    }
    if (droppedWeeks > 0) {
        pushWarning('有 ' + droppedWeeks + ' 个周次超出 ' + MAX_WEEKS + ' 周（课表数据的上限），没有导入');
    }
    if (clamped > 0) {
        pushWarning('有 ' + clamped + ' 条排课记录的节次超出作息表的 ' + PERIOD_TIMES.length +
            ' 节，已并入第 ' + PERIOD_TIMES.length + ' 节，请在课表里核对');
    }
    if (omittedWarnings > 0) {
        warnings.push('还有 ' + omittedWarnings + ' 条提示没有显示（最多 ' + MAX_WARNINGS + ' 条）');
    }

    var courses = [];
    for (var ci = 0; ci < courseOrder.length; ci++) courses.push(courseIndex[courseOrder[ci]]);

    var termName = text(term.name) || termNameFrom(termCode) || '教务导入';

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
