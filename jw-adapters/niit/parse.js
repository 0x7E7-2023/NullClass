(function () {
    // 南京工业职业技术大学教务适配器（金智教育 WIS / jwapp 平台）—— 原始数据 → 空课课表载荷
    //
    // 移植自 shiguang_warehouse 的 NIIT/niit.js（MIT，上游作者 nifs1729）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游快照：main @ e62554a（2026-09-12）
    //
    // 这一段是纯转换（不碰 DOM、不发请求），所以能在 CI 里用 Rhino 跑回归。
    //
    // 移植改动（与上游的语义差异，逐条列在这里）：
    //   ① 周次：上游发显式的 weeks 数组，这里按空课载荷切成极大段 —— 步长 1 是每周（ALL），
    //      步长 2 是单/双周（ODD/EVEN），落单的一周单独成段。见移植手册 §4.1。
    //   ② 教师 / 教室：上游给未知值兜底写「待定」，空课会把「待定」当成真名显示，所以留 null。
    //      多人教师（"张三/李四"）原样保留，不再按 "/" 截断 —— 截断是悄悄丢人。
    //   ③ 上游按「星期 + 节次 + 课名 + 教师 + 教室」去重后合并周次；这里按课程聚合、每行一条安排。
    //      两者的周次集合相同（我们的 blocks 本来就是列表），但同一门课在同一时段的两个不同
    //      地点不会被并掉。
    //   ④ 总周数：优先用校历给的 ZZC，其次用最长周次位串的长度（上游口径），最后才是课表里出现
    //      的最大周次 —— 都是在 extract 交出来的 term 上做选择，这里不做新的取数。
    //   ⑤ 开学日：extract 拿不到校历时会给一个按「最近的周一」推算的值并标
    //      firstDaySource="guess"；这里照用，并按手册 §4.2 写进 warnings。
    //      校历给的真值也按 §4.3 回退到周一（上游 firstDayOfWeek = 1）。
    var data = JSON.parse(typeof __ncInput !== 'undefined' ? __ncInput : '{}');
    var rows = data.rows || [];
    var term = data.term || {};

    // 载荷校验要求 totalWeeks ∈ 1..30（规范 §4）
    var MAX_WEEKS = 30;
    var FALLBACK_WEEKS = 20;

    // 本校作息时间（11 节），取自上游脚本的 PRESET_TIME_SLOTS
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '13:30', end: '14:15' },
        { periodIndex: 6, start: '14:25', end: '15:10' },
        { periodIndex: 7, start: '15:30', end: '16:15' },
        { periodIndex: 8, start: '16:25', end: '17:10' },
        { periodIndex: 9, start: '18:15', end: '19:00' },
        { periodIndex: 10, start: '19:10', end: '19:55' },
        { periodIndex: 11, start: '20:05', end: '20:50' }
    ];

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
        if (!/^[01]+$/.test(bits)) return weeks;
        for (var i = 0; i < bits.length; i++) {
            if (bits.charAt(i) === '1') weeks.push(i + 1);
        }
        return weeks;
    }

    // 周次集合（升序去重）→ 极大段。步长 1 视作每周，步长 2 视作单/双周。
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

    // 学期编号形如 2026-2027-1（上游从页面 #dqxnxq2 / 本机日期得到的就是这个形状）。
    // 教务给了学期名（校历接口的 MC）就用它，没有就按编号拼一个可读名字 ——
    // 不要拿适配器名当学期名。
    function termNameFrom(code) {
        var m = /^(\d{4})-(\d{4})-(\d{1,2})$/.exec(String(code));
        if (!m) return '';
        var index = parseInt(m[3], 10);
        var label = index === 1 ? '第一学期' : (index === 2 ? '第二学期' : (index === 3 ? '第三学期' : '第' + index + '学期'));
        return m[1] + '-' + m[2] + '学年' + label;
    }

    var warnings = [];

    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var maxBits = 0;
    var skipped = 0;
    var droppedWeeks = 0;

    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var name = text(row.KCM);
        var day = intOf(row.SKXQ);
        var startPeriod = intOf(row.KSJC);
        var endPeriod = intOf(row.JSJC);
        var bits = text(row.SKZC);
        var isBitString = /^[01]+$/.test(bits);

        // 完全空白的占位行（课名 / 星期 / 周次都没有）不是「被丢掉的课」，不计入跳过数
        if (!name && !bits && !text(row.SKXQ)) continue;

        // 位串长度就是教务眼里的学期周数（上游口径），只在它确实是位串时才算
        if (isBitString && bits.length > maxBits) maxBits = bits.length;

        var weeks = isBitString ? weeksFromBits(bits) : [];
        if (weeks.length > 0) {
            var kept = [];
            for (var w = 0; w < weeks.length; w++) {
                if (weeks[w] <= MAX_WEEKS) kept.push(weeks[w]);
                else droppedWeeks++;
            }
            weeks = kept;
        }

        if (!name || !(day >= 1 && day <= 7) || !(startPeriod >= 1) ||
            !(endPeriod >= startPeriod) || !weeks.length) {
            skipped++;
            continue;
        }

        var teacher = text(row.SKJS) || null;
        var location = text(row.JASMC) || null;
        var key = name + '\u0000' + (teacher || '');
        if (!byCourse[key]) {
            byCourse[key] = { name: name, teacher: teacher, note: null, blocks: [] };
            order.push(key);
        }

        var runs = runsOf(weeks);
        for (var r = 0; r < runs.length; r++) {
            var run = runs[r];
            if (run.end > maxWeek) maxWeek = run.end;
            byCourse[key].blocks.push({
                dayOfWeek: day,
                startPeriod: startPeriod,
                endPeriod: endPeriod,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: location
            });
        }
    }

    var termCode = text(term.code);
    if (order.length === 0) {
        // 有课但全被周次上限挡掉时，别把原因说成「教务改了格式」
        var hint = droppedWeeks > 0
            ? '：课表里的周次都超过了 ' + MAX_WEEKS + ' 周（课表数据的上限），放不进空课'
            : '：可能是还没排课，或教务系统改了课表的数据格式。请在教务页面里确认课表已经显示出来再重试';
        throw new Error('没解析到任何课程' + (termCode ? '（学期编号 ' + termCode + '）' : '') + hint);
    }

    // ---- 学期元信息 ----------------------------------------------------------

    // 学期编号是推算出来的（页面和接口都没给出）时要说清楚：猜错学期＝整张课表都错，
    // 而推算值和真值在库里长得一模一样。
    if (text(term.source) === 'guess') {
        warnings.push('学期编号 ' + termCode + ' 是按本机日期推算的（没能从教务获取），请核对导入的是不是本学期');
    }

    var firstDay = mondayOnOrBefore(term.firstDay);
    var firstDayGuessed = !firstDay || text(term.firstDaySource) === 'guess';
    if (!firstDay) firstDay = mondayOnOrBefore(currentMondayIso());
    if (firstDayGuessed) {
        warnings.push('开学日期无法从教务获取，已按最近的周一推算，请在学期管理里核对');
    }

    var calendarWeeks = intOf(term.totalWeeks);
    if (calendarWeeks !== null && (calendarWeeks < 1 || calendarWeeks > MAX_WEEKS)) calendarWeeks = null;

    var totalWeeks = calendarWeeks;
    if (totalWeeks === null) totalWeeks = maxBits > 0 ? maxBits : maxWeek;
    if (!(totalWeeks >= 1)) totalWeeks = FALLBACK_WEEKS;
    if (totalWeeks < maxWeek) totalWeeks = maxWeek;
    if (totalWeeks > MAX_WEEKS) totalWeeks = MAX_WEEKS;

    if (calendarWeeks !== null && maxWeek > calendarWeeks) {
        warnings.push('教务给的学期总周数是 ' + calendarWeeks + ' 周，但课表里有第 ' + maxWeek +
            ' 周的课，已按 ' + totalWeeks + ' 周导入，请核对');
    }
    if (skipped > 0) {
        warnings.push('有 ' + skipped + ' 条排课记录缺少课名、星期、节次或周次，没有导入');
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 个周次超出 ' + MAX_WEEKS + ' 周（课表数据的上限），没有导入');
    }

    var courses = [];
    for (var c = 0; c < order.length; c++) courses.push(byCourse[order[c]]);

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
