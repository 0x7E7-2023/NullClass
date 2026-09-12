(function () {
    // 中国石油大学（华东）本科教务适配器（湖南强智 · 学生端 /jsxsd/）—— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 UPC/upc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //
    // 上游的核心是 parseTimetableToModel() + parseWeeks()：读 #timetable 每个格子里
    // div.kbcontent 的明细，按「课名（无 title 的 <font>）+ font[title=教师|周次(节次)|教室]」
    // 取字段。字段来源保持一致，语义改了六处（逐条对照见 AUDIT.md「与 hynu 的同平台对照」）：
    //   ① 课名取「没有 title 的 <font>」—— 上游就是这个选择器。**不能照抄 hynu**：那边课名是
    //      第一个 <font> 之前的纯文字，这边课名本身包在无 title 的 <font> 里，换过来一门课都读不出。
    //      这里以无 title 的 <font> 为主，另外两条（font 前的文字、font[title=课程]）只作兜底。
    //   ② 周次认「单/双」，且认写在「周」字后面的写法（1-16周(双)）。上游 weekStr.split('(')[0]
    //      会把括号连同标记一起丢掉 —— 双周课退化成「每周都上」且不报警（第一批 hbmu 的真实故障）。
    //   ③ 星期按表头列号对齐。上游把「第几个格子」直接当星期几（第一格也当周日），首列是节次列时
    //      周日会被算两次；这里认表头里的「星期一…星期日」，并保留无表头时的兜底。
    //   ④ 节次除方括号里的 [01-02节] 外，还认 [0102节]（两位一节）、[09,10节]；格子自己读不出节次时
    //      回落本行的「第N节」标签并进 warnings，不再像上游那样静默丢块（上游要求 start > 0 才收）。
    //   ⑤ 超出内置 12 节的作息表按每节 45 分钟顺延补出并进 warnings（宿主的默认作息表只在
    //      periodTimes 整个为空时才顶上来，缺一半会一路带进课表）；顺延到的时刻必须还在
    //      00:00-23:59 内，再往后（第 15 节会算出 24:10）就不补了，课块保留、只给 warnings
    //      —— 载荷的时间校验只收 HH:mm，补出「24:10」会让整次导入失败。
    //   ⑥ 周次/节次/课名读不出的块逐条计数进 warnings（不许静默丢数据）。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { now: "2026-09-12", term: { code, name }, rows: [ [ { text, parts: [原始 HTML] }, … ], … ] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（12 节），上游硬编码在 saveAppTimeSlots() 里，逐条照搬。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:50', end: '09:35' },
        { periodIndex: 3, start: '09:55', end: '10:40' },
        { periodIndex: 4, start: '10:45', end: '11:30' },
        { periodIndex: 5, start: '11:35', end: '12:20' },
        { periodIndex: 6, start: '14:00', end: '14:45' },
        { periodIndex: 7, start: '14:50', end: '15:35' },
        { periodIndex: 8, start: '15:55', end: '16:40' },
        { periodIndex: 9, start: '16:45', end: '17:30' },
        { periodIndex: 10, start: '19:00', end: '19:45' },
        { periodIndex: 11, start: '19:50', end: '20:35' },
        { periodIndex: 12, start: '20:40', end: '21:25' }
    ];
    // 内置作息表的节数（12）。顺延补出来的作息只往后加，不改上面这 12 条。
    var BUILTIN_PERIODS = PERIOD_TIMES.length;

    // 上游 saveAppConfig() 只写了 firstDayOfWeek = 7（周日）—— 它没有开学日。
    // 我们的 firstDay 是「第 1 周的第一天」，所以推算值要回退到周日（手册 §4.3），
    // 这个口径与同为强智的 hynu（firstDayOfWeek = 1，周一）**不一样**，见 AUDIT.md。
    var FIRST_DAY_OF_WEEK = 7;
    // 上游没有学期总周数（saveCourseConfig 里只有 firstDayOfWeek），只能内置 + 被更晚的周次抬高。
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验的总周数上限（1..30）：超出的周次按脏数据丢弃并进 warnings。
    var MAX_WEEK = 30;
    // 节次上限：只为挡住解析跑飞（例如把 [12345678节] 按两位切成 12/34/56/78）。
    // 作息表最远只能补到第 14 节（第 15 节会算出 24:10，越过 23:59），第 15 节起的课块保留但没有时刻。
    var MAX_PERIOD = 20;
    var PERIOD_MINUTES = 45;
    var PERIOD_BREAK_MINUTES = 10;
    // 一格里的多门课用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上，并把 <hr> 也算上）。
    var DASHES = /-{5,}|<hr\b[^>]*>/i;
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    // 只认装着周次/节次的那几条 font 标题，**逐个精确匹配**：上游 UPC/upc.js 只
    // querySelector 了 font[title="教师"]、font[title="周次(节次)"]、font[title="教室"] 三个，
    // 同平台的别的部署还有把周次与节次拆成 font[title="周次"] + font[title="节次"] 两条的，
    // 这三种写法都认（见 splitWeekPeriod）。
    // **不许放宽到「时间」**（那是移植时自己加的，上游没有）：有的页面拿 font[title="上课时间"]
    // 装的是钟点区间（08:00-09:35），那种文本按周次去解会从 "00-09" 里凭空造出 1-9 周 —— 整格
    // 课会多出一条 [1,9] 的排课，而且不报警（同批 gxdlxy 的 parse.js 是同一个暴露面）。
    var SPEC_TITLE = /^(周次\s*[（(]\s*节次\s*[）)]|周次|节次)$/;
    // 复合键的分隔符：用 NUL 防止「课名 + 教师」拼串撞车。
    // **不写字面 NUL 字节**（那会让 grep 把源文件当二进制），运行时用 fromCharCode 生成。
    var KEY_SEP = String.fromCharCode(0);

    var droppedWeeks = 0;
    var dirtyPeriods = 0;
    var skippedName = 0;
    var skippedWeeks = 0;
    var skippedPeriods = 0;
    var rowPeriodFallbacks = 0;
    var mixedMarks = 0;

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    function toMinutes(text) {
        var m = /^(\d{1,2}):(\d{2})$/.exec(clean(text));
        if (!m) return null;
        return parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
    }

    function hhmm(minutes) {
        var h = Math.floor(minutes / 60);
        var m = minutes % 60;
        return pad2(h) + ':' + pad2(m);
    }

    // 提取时刻所在周的第 1 天（firstDayOfWeek = 7 → 周日）。
    // 不用 Date.parse：Rhino 对 ISO 串的支持不齐。
    function weekStartOf(isoDate, firstDayOfWeek) {
        var m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(clean(isoDate));
        if (!m) return null;
        var day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
        // getDay(): 0 = 周日。回退到「星期几 = firstDayOfWeek」的那一天（含当天）
        var back = (day.getDay() + 7 - (firstDayOfWeek % 7)) % 7;
        return isoOf(new Date(day.getFullYear(), day.getMonth(), day.getDate() - back));
    }

    function stripTags(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/<[^>]*>/g, '');
    }

    function decodeEntities(value) {
        return String(value)
            .replace(/&nbsp;/gi, ' ')
            .replace(/&lt;/gi, '<')
            .replace(/&gt;/gi, '>')
            .replace(/&quot;/gi, '"')
            .replace(/&#39;/g, '\'')
            .replace(/&amp;/gi, '&');
    }

    function plainText(value) {
        return clean(decodeEntities(stripTags(value)));
    }

    // 块里还剩不剩「内容」：只有一条分隔线 / 几个分隔符的块是强智用来隔开多门课的，不是课程。
    // 这种块静默跳过（不进 warnings 的丢数据计数），否则每个空行都会被算成「丢了一门课」。
    function contentText(value) {
        return plainText(String(value).replace(/-{2,}/g, ' ').replace(/[|｜·]/g, ' '));
    }

    // 名字里只有数字 / 破折号 / 「单双周第节」/ 括号的，是节次残片或分隔符，不是课名
    //（造一门叫「3-4」的课比跳过它更糟；跳过的会进 warnings，不静默）。
    var JUNK_NAME = /^[\s0-9\-—~至,，、;；.。()（）单双周第节]+$/;

    function usableName(value) {
        var text = clean(value);
        if (!text || JUNK_NAME.test(text)) return '';
        return text;
    }

    function titleAttr(attrs) {
        var m = /title\s*=\s*["']?([^"'>]+)["']?/i.exec(String(attrs));
        return m ? clean(m[1]) : '';
    }

    // 明细 HTML 里的 <font>，按出现顺序返回；title 缺省记成空串（上游的课名选择器就是靠这一点）。
    // 末尾的 </font> 允许缺失（教务页面的 <font> 偶尔不闭合），那时取到下一个 '<' 为止。
    function fontsOf(blockHtml) {
        var html = String(blockHtml);
        var re = /<font\b([^>]*)>([\s\S]*?)(?:<\/font>|(?=<)|$)/gi;
        var out = [];
        var m = re.exec(html);
        while (m) {
            out.push({ title: titleAttr(m[1]), text: plainText(m[2]) });
            m = re.exec(html);
        }
        return out;
    }

    // 课名：① 第一个无 title 的 <font>（上游选择器）；② font 之前的纯文字（别的强智部署）；
    // ③ font[title=课程]。三条都读不出才算这一块没课名（进 warnings，不静默丢）。
    function nameOf(blockHtml) {
        var fonts = fontsOf(blockHtml);
        for (var i = 0; i < fonts.length; i++) {
            if (!fonts[i].title) {
                var byFont = usableName(fonts[i].text);
                if (byFont) return byFont;
            }
        }
        var lines = String(blockHtml).split(/<font\b/i)[0].split(/<br\s*\/?>/i);
        for (var j = 0; j < lines.length; j++) {
            var byText = usableName(plainText(lines[j]));
            if (byText) return byText;
        }
        for (var k = 0; k < fonts.length; k++) {
            if (COURSE_TITLE.test(fonts[k].title)) {
                var byTitle = usableName(fonts[k].text);
                if (byTitle) return byTitle;
            }
        }
        return '';
    }

    function titleTexts(fonts, pattern) {
        var out = [];
        for (var i = 0; i < fonts.length; i++) {
            if (!pattern.test(fonts[i].title)) continue;
            var text = fonts[i].text;
            if (text && out.indexOf(text) < 0) out.push(text);
        }
        return out;
    }

    // "周次(节次)" 一条 font 里既有周次也有节次（上游这所学校就是），
    // 有的强智部署拆成「周次」「节次」两条 font —— 两种都按「方括号前是周次、方括号里是节次」切。
    function splitWeekPeriod(value) {
        var text = clean(value);
        var bracket = /\[([^\]]*)\]/.exec(text);
        if (!bracket) return { weeks: text, periods: '' };
        return {
            weeks: clean(text.substring(0, bracket.index) + ' ' + text.substring(bracket.index + bracket[0].length)),
            periods: clean(bracket[1])
        };
    }

    // 把「周次」「节次」两类 font 配成一条排课记录：一条合写的（周次(节次)）直接成对，
    // 拆写的（周次 + 节次）相邻两条配成一对。
    function specsOf(blockHtml) {
        var fonts = fontsOf(blockHtml);
        var specs = [];
        var pending = null;
        for (var i = 0; i < fonts.length; i++) {
            if (!SPEC_TITLE.test(fonts[i].title)) continue;
            var split = splitWeekPeriod(fonts[i].text);
            if (!split.weeks && !split.periods) continue;
            if (split.weeks && split.periods) {
                if (pending) specs.push(pending);
                pending = null;
                specs.push(split);
            } else if (split.weeks) {
                if (pending && !pending.weeks) {
                    pending.weeks = split.weeks;
                    specs.push(pending);
                    pending = null;
                } else {
                    if (pending) specs.push(pending);
                    pending = { weeks: split.weeks, periods: '' };
                }
            } else if (pending && !pending.periods) {
                pending.periods = split.periods;
                specs.push(pending);
                pending = null;
            } else {
                if (pending) specs.push(pending);
                pending = { weeks: '', periods: split.periods };
            }
        }
        if (pending) specs.push(pending);
        return specs;
    }

    function pushWeek(out, week, oddOnly, evenOnly) {
        if (week < 1) return;
        if (week > MAX_WEEK) {
            droppedWeeks++;
            return;
        }
        if (oddOnly && week % 2 === 0) return;
        if (evenOnly && week % 2 === 1) return;
        out.push(week);
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

    // 周次文本 → 周次数组。这一段是上游最薄的地方（它只做 split('(')[0]），也是本批次最容易
    // 写错的地方，逐条说明：
    //   · 「1-16周(双)」「1-15(单)」「双周2-6」「单周1-9,11-17」「第3周」「1-4,6-8,10-16」都要认；
    //   · 括号里的纯数字**不是**周次（那是课程序号之类的），所以先把括号整段去掉再取数字；
    //     但括号里的「单」「双」是周次标记，必须在去掉括号之前先读出来；
    //   · 整串开头的「单周/双周」是全局修饰，只作用于没带自己标记的分段；
    //   · 带标记的分段与不带标记的分段混在一串里（"1-8周,10-16周(双)"）语义有歧义 ——
    //     按字面各段自判，并计数进 warnings（不许猜、也不许静默）。
    function weeksIn(source) {
        var text = clean(source).replace(/\[[^\]]*\]/g, ' ');
        // 节次写在方括号外面时（"第1-2节 1-16周"），那些数字不是周次
        text = text.replace(/第?\s*\d{1,2}\s*[-—~至,，、]\s*\d{1,2}\s*节/g, ' ');
        text = text.replace(/第?\s*\d{1,2}\s*节/g, ' ');
        var globalType = '';
        var head = /^\s*(单|双)\s*周?/.exec(text);
        if (head) {
            globalType = head[1];
            text = clean(text.substring(head[0].length));
        }
        if (/单\s*双|双\s*单/.test(text)) globalType = '';

        var segments = text.split(/[,，、;；]/);
        var weeks = [];
        var pending = '';
        var markedSegments = 0;
        var plainSegments = 0;
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var type = globalType;
            var mark = /(单|双)/.exec(segment);
            if (mark) type = mark[1];
            if (/单\s*双|双\s*单/.test(segment)) type = '';
            // 括号整段去掉（纯数字序号不是周次），再抹掉「单」「双」「周」「第」
            var body = segment.replace(/[（(][^）)]*[）)]/g, ' ')
                .replace(/单|双/g, ' ')
                .replace(/周/g, ' ')
                .replace(/第/g, ' ');
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(body);
            var start = null;
            var end = null;
            if (range) {
                start = parseInt(range[1], 10);
                end = parseInt(range[2], 10);
            } else {
                var single = /(\d{1,2})/.exec(body);
                if (single) {
                    start = parseInt(single[1], 10);
                    end = start;
                }
            }
            if (start === null) {
                // 这一段只有标记没有数字（"1-16,双"）：并给最近的一个周次段
                if (mark) pending = mark[1];
                continue;
            }
            if (!type && pending) type = pending;
            pending = '';
            if (type) markedSegments++;
            else if (!globalType) plainSegments++;
            for (var w = start; w <= end; w++) {
                pushWeek(weeks, w, type === '单', type === '双');
            }
        }
        if (!globalType && markedSegments > 0 && plainSegments > 0) mixedMarks++;
        return uniqueSorted(weeks);
    }

    // 节次："01-02节" / "1-2节" / "0102节"（两位一节）/ "09,10节" / "第13-14节" 都认。
    function periodsIn(source) {
        var body = clean(source).replace(/[第节\s]/g, '').replace(/[（）()]/g, '');
        if (!body) return null;
        var start = null;
        var end = null;
        function note(value) {
            if (!(value >= 1)) return;
            if (start === null || value < start) start = value;
            if (end === null || value > end) end = value;
        }
        var segments = body.split(/[,，]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var range = /^(\d{1,2})\s*[-—~至]\s*(\d{1,2})$/.exec(segment);
            if (range) {
                note(parseInt(range[1], 10));
                note(parseInt(range[2], 10));
                continue;
            }
            if (!/^\d+$/.test(segment)) return null;
            if (segment.length >= 4 && segment.length % 2 === 0) {
                for (var k = 0; k < segment.length; k += 2) note(parseInt(segment.substring(k, k + 2), 10));
            } else {
                note(parseInt(segment, 10));
            }
        }
        if (start === null || end === null) return null;
        return { start: start, end: end };
    }

    // 周次集合 → 极大段（手册 §4.1）：步长 1 视作每周，步长 2 视作单/双周
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

    var DAY_CHARS = '一二三四五六日天';

    function dayOfLabel(value) {
        var m = /(?:星期|周|礼拜)\s*([一二三四五六日天1-7])/.exec(clean(value));
        if (!m) return 0;
        var at = DAY_CHARS.indexOf(m[1]);
        if (at >= 0) return m[1] === '天' ? 7 : at + 1;
        return parseInt(m[1], 10);
    }

    function termNameFromCode(code) {
        var m = /^(\d{4})-(\d{4})-(\d)$/.exec(clean(code));
        if (!m) return '';
        if (m[3] === '1') return m[1] + '-' + m[2] + '学年第一学期';
        if (m[3] === '2') return m[1] + '-' + m[2] + '学年第二学期';
        if (m[3] === '3') return m[1] + '-' + m[2] + '学年第三学期';
        return m[1] + '-' + m[2] + '学年第' + m[3] + '学期';
    }

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的列号取每天那一列。
    // 上游把「第几个格子」直接当星期几（第一格当周日），课表首列是节次列时会错一天。
    var headerRow = -1;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    for (var r = 0; r < rows.length; r++) {
        var hasContent = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        for (var c = 0; c < rows[r].length; c++) {
            if (rows[r][c].parts && rows[r][c].parts.length) hasContent = true;
            var day = dayOfLabel(rows[r][c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = c;
                labels++;
            }
        }
        if (hasContent || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            dayCol = cols;
        }
    }

    var order = [];
    var byCourse = {};

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        // 这一行的「第N节」标签：格子自己读不出节次时用它兜底
        var rowPeriods = periodsIn(row.length ? row[0].text : '');
        // 没认出表头时的兜底：多于 7 列就按「最后 7 列是周一到周日」算
        var offset = bestLabels >= 4 ? 0 : (row.length > 7 ? row.length - 7 : 0);
        for (var d = 1; d <= 7; d++) {
            var col = bestLabels >= 4 ? dayCol[d] : offset + d - 1;
            if (col < 0 || col >= row.length) continue;
            var parts = row[col].parts || [];
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (contentText(blockHtml) === '') continue;
                    var name = nameOf(blockHtml);
                    if (!name) {
                        skippedName++;
                        continue;
                    }
                    var fonts = fontsOf(blockHtml);
                    var teachers = titleTexts(fonts, TEACHER_TITLE);
                    var rooms = titleTexts(fonts, ROOM_TITLE);
                    var specs = specsOf(blockHtml);
                    if (!specs.length) {
                        skippedWeeks++;
                        continue;
                    }
                    var course = null;
                    for (var s = 0; s < specs.length; s++) {
                        var weeks = weeksIn(specs[s].weeks);
                        var periods = periodsIn(specs[s].periods);
                        var fromRow = false;
                        if (!periods && rowPeriods) {
                            periods = rowPeriods;
                            fromRow = true;
                        }
                        if (!weeks.length) {
                            skippedWeeks++;
                            continue;
                        }
                        if (!periods) {
                            skippedPeriods++;
                            continue;
                        }
                        if (periods.start > MAX_PERIOD || periods.end > MAX_PERIOD) {
                            dirtyPeriods++;
                            continue;
                        }
                        if (fromRow) rowPeriodFallbacks++;
                        // 课程要等真产出一条安排才登记：周次/节次解析不出来的块只进 warnings，
                        // 不能在课表里留下一门「没有任何安排」的空课。
                        if (!course) {
                            var key = name + KEY_SEP + teachers.join(',');
                            if (!byCourse[key]) {
                                byCourse[key] = {
                                    name: name,
                                    teacher: teachers.length ? teachers.join(',') : null,
                                    note: null,
                                    blocks: [],
                                    seen: {}
                                };
                                order.push(key);
                            }
                            course = byCourse[key];
                        }
                        var runs = runsOf(weeks);
                        for (var n = 0; n < runs.length; n++) {
                            var run = runs[n];
                            var block = {
                                dayOfWeek: d,
                                startPeriod: periods.start,
                                endPeriod: periods.end,
                                startWeek: run.start,
                                endWeek: run.end,
                                weekType: run.weekType,
                                location: rooms.length ? rooms[0] : null
                            };
                            var blockKey = [block.dayOfWeek, block.startPeriod, block.endPeriod,
                                block.startWeek, block.endWeek, block.weekType,
                                block.location || ''].join('|');
                            if (course.seen[blockKey]) continue;
                            course.seen[blockKey] = true;
                            course.blocks.push(block);
                        }
                    }
                }
            }
        }
    }

    if (order.length === 0) {
        throw new Error(
            '本学期没有解析到任何课程：可能是还没排课，或教务页面改了结构（也可能登录状态已失效）'
        );
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var item = byCourse[order[oi]];
        for (var bi = 0; bi < item.blocks.length; bi++) {
            if (item.blocks[bi].endWeek > maxWeek) maxWeek = item.blocks[bi].endWeek;
            if (item.blocks[bi].endPeriod > maxPeriod) maxPeriod = item.blocks[bi].endPeriod;
        }
        courses.push({ name: item.name, teacher: item.teacher, note: item.note, blocks: item.blocks });
    }

    // 总周数：教务与上游都不给，内置 20 周；课表里出现更晚的周次时按它抬高（并进 warnings）。
    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    // 作息表覆盖到实际出现的每一节：超出内置 12 节的按每节 45 分钟、课间 10 分钟顺延补出。
    // **补出来的时刻必须落在 00:00-23:59 内**：载荷的时间只收 HH:mm（JwPayloadCodec 的
    // TIME_REGEX 是 00:00-23:59），第 15 节顺延出的是 23:25-24:10 —— 一个非法时刻会让
    // **整次导入失败**（不是丢掉这一条，是全盘拒绝）。所以补不下去就停在能补到的最后一节：
    // 那些节次的课块照旧保留（节次本身是合法数据），只是没有上下课时间，并进 warnings 说清楚。
    var LAST_MINUTE_OF_DAY = 23 * 60 + 59;
    var extendedPeriods = 0;
    var known = PERIOD_TIMES.length;
    while (known < maxPeriod) {
        var prevEnd = toMinutes(PERIOD_TIMES[known - 1].end);
        if (prevEnd === null) break;
        var nextStart = prevEnd + PERIOD_BREAK_MINUTES;
        var nextEnd = nextStart + PERIOD_MINUTES;
        if (nextEnd > LAST_MINUTE_OF_DAY) break;
        PERIOD_TIMES.push({
            periodIndex: known + 1,
            start: hhmm(nextStart),
            end: hhmm(nextEnd)
        });
        known++;
        extendedPeriods++;
    }
    // 有课块的节次落在「补不出时间」的那一段里：课块留着，但没有时刻可显示，得让用户知道。
    var untimedBlocks = 0;
    for (var ui = 0; ui < courses.length; ui++) {
        var ublocks = courses[ui].blocks;
        for (var ub = 0; ub < ublocks.length; ub++) {
            if (ublocks[ub].endPeriod > known) untimedBlocks++;
        }
    }

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期。
    var firstDay = weekStartOf(data.now, FIRST_DAY_OF_WEEK);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（手册 §4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    var warnings = [
        '开学日期无法从教务获取，已按提取当天的周首日（' + firstDay + '，周日）推算，' +
            '请在「学期管理」里核对（该口径取自上游适配器声明的 firstDayOfWeek=7）',
        '教务不给学期总周数与作息时间：总周数按 ' + totalWeeks + ' 周、节次时间按适配器内置的 ' +
            BUILTIN_PERIODS + ' 节设定，请在「学期管理」里核对'
    ];
    if (maxWeek > DEFAULT_TOTAL_WEEKS) {
        warnings.push('课表里出现了第 ' + maxWeek + ' 周的课，学期总周数已按最晚周次抬高到 ' + totalWeeks + ' 周，请核对');
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 个周次超出 ' + MAX_WEEK + ' 周的上限，已按脏数据丢弃');
    }
    if (extendedPeriods > 0) {
        warnings.push('课表里出现了第 ' + maxPeriod + ' 节：内置作息表只有 ' + BUILTIN_PERIODS + ' 节，' +
            '第 ' + (BUILTIN_PERIODS + 1) + ' 节起已按每节 ' + PERIOD_MINUTES + ' 分钟顺延补出' +
            '（补到第 ' + known + ' 节），请核对');
    }
    if (untimedBlocks > 0) {
        warnings.push('有 ' + untimedBlocks + ' 个课程块用到了第 ' + (known + 1) + ' 节及以后：' +
            '再往后顺延就超过 23:59，作息表补不出这些节次的上下课时间。课块已保留，' +
            '但没有时刻、课表上也画不出来，请核对');
    }
    if (rowPeriodFallbacks > 0) {
        warnings.push('有 ' + rowPeriodFallbacks + ' 个课程块的节次标签不完整，已按所在行的「第N节」标签定位');
    }
    if (skippedName > 0) {
        warnings.push('有 ' + skippedName + ' 个课程块没能认出课程名，已跳过（教务页面结构可能已调整，欢迎反馈）');
    }
    if (skippedWeeks > 0) {
        warnings.push('有 ' + skippedWeeks + ' 个课程块没能解析出周次（或整块就没有周次字段，例如只写了「见通知」），已跳过');
    }
    if (skippedPeriods > 0) {
        warnings.push('有 ' + skippedPeriods + ' 个课程块没能解析出节次，已跳过');
    }
    if (dirtyPeriods > 0) {
        warnings.push('有 ' + dirtyPeriods + ' 个课程块的节次超出 ' + MAX_PERIOD + ' 节，已按脏数据跳过');
    }
    if (mixedMarks > 0) {
        warnings.push('有 ' + mixedMarks + ' 个课程块的周次里只有部分分段带「单/双」标记，' +
            '已按字面各段自判（例如「1-8周,10-16周(双)」按前半每周、后半双周处理），请核对');
    }
    if (bestLabels < 4) {
        warnings.push('课表里没找到星期表头，已按每行最后 7 列对应周一到周日，请核对导入结果');
    }
    if (!termName) {
        termName = '中国石油大学（华东）课表';
        warnings.push('没能识别出学年学期名称，学期名已用「中国石油大学（华东）课表」占位，请在「学期管理」里改名');
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
                periodTimes: PERIOD_TIMES,
                courses: courses
            }
        ]
    });
})()
