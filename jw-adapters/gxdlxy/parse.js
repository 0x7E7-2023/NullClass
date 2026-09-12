(function () {
    // 广西电力职业技术学院（强智 · 高校综合管理教务系统学生端 /jsxsd/）课表解析
    // 移植自 shiguang_warehouse 的 GXDLXY/gxdlxy_01.js（MIT，作者 星河欲转）
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse
    // 上游的核心是 parseTimetableToModel() + parseWeeks() + mergeAndDistinctCourses()：
    // 读 #kbtable 里每个格子 div.kbcontent 的明细，按「课程名 / font[title=老师|周次(节次)|教室]」
    // 取字段（教师那条 font 的 title，上游这一份写的是「老师」，hynu 上游写的是「教师」），
    // 再把相邻节次的同一门课合并。
    //
    // 它和同平台的 hynu 只有 0.739 相似度，四处实质差异决定了这里**不能照抄 hynu 那一份**
    // （逐条对照见 AUDIT.md）：
    //   ① 节次从哪读：上游读的是**教室**字段里的 "[01-02]节"（按行尾锚定，读完再从教室名里删掉）；
    //      hynu 那一支读的是「周次(节次)」字段里的 "[01-02节]"。这里两个位置都认，且优先
    //      「周次(节次)」—— 其它强智学校的实测形态（同平台 BTBU 适配器对着真实页面校准过）
    //      是 "[01-02节]" 写在「周次(节次)」里，上游这个读法在本校页面若是另一种形态就会
    //      一门课都读不出来。两个都读不到时，再用**所在行的节次标签**（第1-2节 / 第一大节）兜底；
    //      连兜底都读不出才跳过，并计数进 warnings。
    //      上游的判据是「课程名非空且 startSection 大于 0」：读不出节次就**静默丢课**，
    //      页面上看不见任何异常。
    //   ② 连堂合并：上游有 mergeAndDistinctCourses()（同一天 + 同一周次 + 同一教室、节次相邻的
    //      两条合成一条，如 1-2 节 + 3-4 节 → 1-4 节），hynu 那一支没有这一步。这里按上游原意保留。
    //   ③ 周次里的单双：上游 parseWeeks() 用 replace(/周|\(.*?\)/g,'') 洗字符串，会把
    //      「1-16周(双)」里的 (双) 一起洗掉（hynu 上游的 split('(')[0] 是同一类错，换了写法），
    //      单双周会静默退化成「每周都上」。这里按空课规范切极大段 + weekType。
    //   ④ 星期对齐：上游把格子在本行里的下标直接当星期几（第几个格子就是星期几），
    //      首列是节次列时整表错一天。这里按表头的星期标签对齐列号（并把 colspan 算进去）。
    //
    // 输入是 extract.js 交出来的原始结构：
    //   { now: "2026-09-12", term: { code, name },
    //     rows: [ [ { text, parts: [原始 HTML], col, span }, … ], … ] }
    // 这里不碰 DOM（CI 的 Rhino 里没有），全部按字符串 + 正则处理，是纯函数。
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var term = data.term || {};

    // 学校作息时间（教务处公布的 10 小节）。上游硬编码在 saveCourseDataToApp() 里，逐条照搬。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:10' },
        { periodIndex: 2, start: '09:20', end: '10:00' },
        { periodIndex: 3, start: '10:20', end: '11:00' },
        { periodIndex: 4, start: '11:10', end: '11:50' },
        { periodIndex: 5, start: '14:30', end: '15:10' },
        { periodIndex: 6, start: '15:20', end: '16:00' },
        { periodIndex: 7, start: '16:10', end: '16:50' },
        { periodIndex: 8, start: '16:50', end: '17:30' },
        { periodIndex: 9, start: '19:40', end: '20:20' },
        { periodIndex: 10, start: '20:30', end: '21:10' }
    ];

    // 上游的表只到第 10 节，课表里却可能出现更晚的节次。多出来的这几节按**空课自己的默认
    // 模板**（core:model DefaultPeriodTimes 的第 11、12 节）补，并在 warnings 里说明 ——
    // 不补的话那几节课在课表里没有时间，补了就必须说出来它是从哪来的。
    var TAIL_PERIOD_TIMES = [
        { periodIndex: 11, start: '20:20', end: '21:05' },
        { periodIndex: 12, start: '21:15', end: '22:00' }
    ];

    // 上游写死的学期总周数（教务页面不给这个值，只能假定并进 warnings）
    var DEFAULT_TOTAL_WEEKS = 20;
    // 载荷校验的总周数上限（1..30），超出的周次按脏数据丢弃并进 warnings
    var MAX_WEEK = 30;
    // 节次的合理范围（防御性上限）：解析出这个范围之外的节次一律当没解析出来
    var MAX_PERIOD = 20;
    // 一个格子里放多门课时，教务用一长串减号分隔（上游写死 21/22 个，这里放宽到 5 个以上）
    var DASHES = /-{5,}/;
    var TEACHER_TITLE = /^(教师|老师|任课教师|授课教师|教师姓名)$/;
    var ROOM_TITLE = /^(教室|上课地点|上课教室|地点|教室名称)$/;
    var COURSE_TITLE = /^(课程|课程名称|课程名|科目)$/;
    // 「周次(节次)」这一条 font 的 title。**只认上游实际用到的这几个精确写法**：
    // 上游 gxdlxy_01.js 取的是 font[title="周次(节次)"]，同平台实测形态还有拆成「周次」+「节次」的。
    // 这里**不许放宽到「时间」**：有的页面拿 font[title="上课时间"] 装的是钟点区间（08:00-09:35），
    // 那种文本被当周次读会从 "08:00-09:35" 里凭空造出 1-9 周（同批 upc/parse.js 有这个暴露面）。
    var SPEC_TITLE = /周次|节次/;

    var droppedWeeks = 0;
    var skippedBlocks = 0;
    var sectionFromRow = 0;
    var ambiguousWeeks = 0;
    // 「有数字、但看不出星期几」的课程块数（表头只认出一部分时，落在没人认领的列里的课）
    var unmappedBlocks = 0;

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

    // 提取时刻那一周的周一。§4.3：firstDay 是第 1 周的第一天，上游 firstDayOfWeek=1（周一），
    // 所以推算值取「回退到周一」的那天。不用 Date.parse：Rhino 对 ISO 串的支持不齐。
    function mondayOf(isoDate) {
        var m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(clean(isoDate));
        if (!m) return null;
        var day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
        var back = (day.getDay() + 6) % 7;
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

    // 明细 HTML 里的 <font title="…">文本</font>，按出现顺序返回。
    // 末尾的 </font> 允许缺失（教务页面的 <font> 偶尔不闭合），那时取到下一个 '<' 为止。
    function fontsOf(blockHtml) {
        var html = String(blockHtml);
        var re = /<font\b[^>]*title\s*=\s*["']?([^"'>]+)["']?[^>]*>([\s\S]*?)(?:<\/font>|(?=<)|$)/gi;
        var out = [];
        var m = re.exec(html);
        while (m) {
            out.push({ title: clean(m[1]), text: plainText(m[2]) });
            m = re.exec(html);
        }
        return out;
    }

    // 课程名：明细里第一个 <font> 之前的第一行文字（与上游取「第一个非空文本节点」等价）；
    // 有的学校把课名也放进 font[title=课程]，兜底认一下。
    function nameOf(blockHtml) {
        var lines = String(blockHtml).split(/<font\b/i)[0].split(/<br\s*\/?>/i);
        for (var i = 0; i < lines.length; i++) {
            var line = plainText(lines[i]);
            if (line) return line;
        }
        var fonts = fontsOf(blockHtml);
        for (var f = 0; f < fonts.length; f++) {
            if (COURSE_TITLE.test(fonts[f].title) && fonts[f].text) return fonts[f].text;
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

    // 「周次(节次)」一条 font 里既有周次也有节次（强智通用写法）；也有学校拆成「周次」+「节次」
    // 两条 font。带这些 title 的文本按出现顺序拼起来，同时当周次来源与节次来源。
    // 用空格拼是为了让 weeksIn() 按空白分段 —— 两个字段的文本不能粘成一个数。
    function specTextOf(fonts) {
        var out = [];
        for (var i = 0; i < fonts.length; i++) {
            if (SPEC_TITLE.test(fonts[i].title) && fonts[i].text) out.push(fonts[i].text);
        }
        return out.join(' ');
    }

    // 学年学期识别码 "2026-2027-1" → 可读的学期名（教务没给名字时用）
    function termNameFromCode(code) {
        var m = /^(\d{4})-(\d{4})-(\d)$/.exec(clean(code));
        if (!m) return '';
        if (m[3] === '1') return m[1] + '-' + m[2] + '学年第一学期';
        if (m[3] === '2') return m[1] + '-' + m[2] + '学年第二学期';
        if (m[3] === '3') return m[1] + '-' + m[2] + '学年第三学期';
        return m[1] + '-' + m[2] + '学年第' + m[3] + '学期';
    }

    function rangeOf(numbers) {
        var start = null;
        var end = null;
        for (var i = 0; i < numbers.length; i++) {
            var value = numbers[i];
            if (!(value >= 1) || value > MAX_PERIOD) continue;
            if (start === null || value < start) start = value;
            if (end === null || value > end) end = value;
        }
        if (start === null || end === null) return null;
        return { start: start, end: end };
    }

    // 方括号里的节次，四种写法都认（前三种见同平台其它学校的真实页面）：
    //   [01-02节]（强智通用）[01-02]节（上游 GXDLXY 假设的写法）[0708节]（连写）[03-04-05节]（三连排）
    // 取其中所有数字的最小/最大值；数字按两位一节的规则切开，所以 "0708" 是 7、8 节而不是 708。
    function sectionInBrackets(text) {
        var m = /\[([^\]]*)\]/.exec(String(text));
        if (!m) return null;
        var groups = m[1].match(/\d+/g) || [];
        var nums = [];
        for (var i = 0; i < groups.length; i++) {
            var g = groups[i];
            if (g.length >= 4 && g.length % 2 === 0) {
                for (var k = 0; k < g.length; k += 2) nums.push(parseInt(g.substring(k, k + 2), 10));
            } else {
                nums.push(parseInt(g, 10));
            }
        }
        return rangeOf(nums);
    }

    // 教室名里可能粘着节次标记（上游认的 "[01-02]节 机房302" 这种），要去掉再当教室用。
    function removeSection(text) {
        return clean(String(text).replace(/\[\s*\d[\d\s\-—~至]*\]\s*节?/g, ' '));
    }

    // 所在行的节次标签：「第1-2节」「1-2」「第9节」「0102」之外的写法都认；
    // 「第一大节」按一节两小节折算（与作息表一致：10 小节 = 5 大节）。
    var CN_DIGITS = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5,
        '六': 6, '七': 7, '八': 8, '九': 9, '十': 10 };

    function rowSectionOf(text) {
        var label = clean(text);
        if (!label) return null;
        var big = /^第?\s*([一二三四五六七八九十\d]{1,3})\s*大节$/.exec(label);
        if (big) {
            var n = CN_DIGITS[big[1]] || parseInt(big[1], 10);
            if (n >= 1 && n <= 10) return { start: (n - 1) * 2 + 1, end: n * 2 };
            return null;
        }
        var range = /^第?\s*(\d{1,2})\s*[-—~至到]\s*(\d{1,2})\s*节?$/.exec(label);
        if (range) {
            var a = parseInt(range[1], 10);
            var b = parseInt(range[2], 10);
            if (a >= 1 && a <= b && b <= MAX_PERIOD) return { start: a, end: b };
            return null;
        }
        var single = /^第?\s*(\d{1,2})\s*节$/.exec(label);
        if (single) {
            var one = parseInt(single[1], 10);
            if (one >= 1 && one <= MAX_PERIOD) return { start: one, end: one };
        }
        return null;
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

    // 「1-16周 双」这种把单/双标记用**空白**隔开写的形态：两个标记分别是独立的一段，
    // 必须落到前一段的周次范围上（1-16 里只要偶数周）。不这么接的话，「双」自成一段、
    // 里面没有数字，整段被丢掉 —— 单双周会静默退化成每周都上。
    // 用 [\s\S] 而不是"点"：Rhino 与各引擎对换行的处理不一，这里明确匹配任意字符。
    var WEEK_RANGE_RE = /(\d{1,2})\s*[-—~至到]\s*(\d{1,2})/;
    var DIGITS_RE = /[\s\S]*?(\d{1,2})/;

    // 切段 → 周次段：每段 = 一段周次文本 + 挂在它末尾的单双标记。
    // 标记可能是同段里的 (双)，也可能是后一段的「双」（「1-16周 双」）。
    function weekSpans(segments) {
        var spans = [];
        var current = null;
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            var odd = segment.indexOf('单') >= 0;
            var even = segment.indexOf('双') >= 0;
            var oddOnly = odd && !even;
            var evenOnly = even && !odd;
            // 「隔周」这类写法没有单/双字，认不出来时要出声，不能静默当每周都上
            if (!odd && !even && segment.indexOf('隔') >= 0) ambiguousWeeks++;
            var range = WEEK_RANGE_RE.exec(segment);
            var single = DIGITS_RE.exec(segment);
            if (oddOnly || evenOnly) {
                // 标记段：挂到前一段上，不能自己当一段（「1-16周 双」里的「双」就走这里）
                if (current) {
                    current.oddOnly = current.oddOnly || oddOnly;
                    current.evenOnly = current.evenOnly || evenOnly;
                    continue;
                }
                // 前面还没有周次段：「2 双」这种把标记写在前面，挂到后面那一段上
                current = { rangeEnd: -1, singleEnd: -1, oddOnly: oddOnly, evenOnly: evenOnly,
                    start: 0, end: 0, single: 0 };
                spans.push(current);
                if (!range && single) current.single = parseInt(single[1], 10);
                continue;
            }
            if (range) {
                current = { rangeEnd: i, singleEnd: -1, oddOnly: oddOnly, evenOnly: evenOnly,
                    start: parseInt(range[1], 10), end: parseInt(range[2], 10), single: 0 };
                spans.push(current);
                continue;
            }
            // 「1-16周 双」的第二段（「双」）也会命中范围正则，但它的数字不是新周次：
            // 只按上面的标记段处理，不再开新段，也不推周次。
            var active = null;
            for (var a = spans.length - 1; a >= 0; a--) {
                if (spans[a].rangeEnd >= 0) { active = spans[a]; break; }
            }
            if (active) {
                if (single && active.singleEnd < 0) {
                    active.single = parseInt(single[1], 10);
                    active.singleEnd = i;
                }
                continue;
            }
            if (single) {
                current = { rangeEnd: -1, singleEnd: i, oddOnly: oddOnly, evenOnly: evenOnly,
                    start: 0, end: 0, single: parseInt(single[1], 10) };
                spans.push(current);
            }
        }
        return spans;
    }

    // "1-16周" / "1-16周 单" / "1-16周 双" / "1-9,11-17(周)" / "1-15周(单)" / "3(周)" / "第3周" → 周次数组。
    // 单双标记写在「周」字后面、或用空白隔开写（「1-16周 双」）都算。
    // 括号里的纯数字（如 (1)）不是周次：括号内容直接当分隔符，不参与取数。
    function weeksIn(source) {
        var text = clean(source).replace(/\[[^\]]*\]/g, ' ')
            .replace(/[（(]/g, ' ').replace(/[)）]/g, ' ');
        var segments = text.split(/[,，、;；\s]+/);
        var spans = weekSpans(segments);
        var weeks = [];
        for (var i = 0; i < spans.length; i++) {
            var span = spans[i];
            if (span.start >= 1 && span.end >= span.start) {
                for (var w = span.start; w <= span.end; w++) {
                    pushWeek(weeks, w, span.oddOnly, span.evenOnly);
                }
            } else if (span.single >= 1) {
                pushWeek(weeks, span.single, span.oddOnly, span.evenOnly);
            }
        }
        return uniqueSorted(weeks);
    }

    // 周次集合 → 极大段（§4.1）：步长 1 视作每周，步长 2 视作单/双周
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

    function colOf(cell, fallbackIndex) {
        return (typeof cell.col === 'number' && cell.col >= 0) ? cell.col : fallbackIndex;
    }

    // 行的节次标签：取该行第一个「没有课程内容」且长得像节次标签的格子
    function rowSectionOfRow(row) {
        for (var i = 0; i < row.length; i++) {
            if (row[i].parts && row[i].parts.length) continue;
            var section = rowSectionOf(row[i].text);
            if (section) return section;
        }
        return null;
    }

    // 星期表头：找「没有课程内容、且有 ≥4 个星期标签」的那一行，按它的**列号**取每天那一列。
    // 上游把「第几个格子」直接当星期几，课表首列是节次时整张表会错一天。
    // 「有课程内容」的判据除了格子里有明细，还包括「本行第一个非空格子长得像节次标签」——
    // 节次标签只出现在课表正文行，有它的那一行不是表头。
    var headerRow = -1;
    var dayCol = [0, -1, -1, -1, -1, -1, -1, -1];
    var bestLabels = 0;
    for (var r = 0; r < rows.length; r++) {
        var hasContent = false;
        var cols = [0, -1, -1, -1, -1, -1, -1, -1];
        var labels = 0;
        var firstText = null;
        for (var c = 0; c < rows[r].length; c++) {
            if (rows[r][c].parts && rows[r][c].parts.length) hasContent = true;
            if (firstText === null && clean(rows[r][c].text)) firstText = rows[r][c].text;
            var day = dayOfLabel(rows[r][c].text);
            if (day >= 1 && day <= 7 && cols[day] < 0) {
                cols[day] = colOf(rows[r][c], c);
                labels++;
            }
        }
        if (!hasContent && firstText !== null && rowSectionOf(firstText)) hasContent = true;
        if (hasContent || labels < 4) continue;
        if (labels > bestLabels) {
            bestLabels = labels;
            headerRow = r;
            dayCol = cols;
        }
    }

    // 表宽（整张表的列数）：推断星期的兜底是「每行最后 7 列对应周一到周日」，
    // 这里的「列」必须是**整张表**的列。extract.js 不会把没课的格子交出来，
    // 拿某一行最后一格来数会把整表算窄；extract 若显式给了 cols 就以它为准。
    var tableCols = Number(data.cols) || 0;
    for (var tr = 0; tr < rows.length; tr++) {
        for (var tc = 0; tc < rows[tr].length; tc++) {
            var at = colOf(rows[tr][tc], tc);
            if (at + 1 > tableCols) tableCols = at + 1;
        }
    }
    var offset = tableCols > 7 ? tableCols - 7 : 0;

    // 每天对应哪一列：表头认出来的优先；**表头没认出来的天**（如周末表头是图片 /
    // 表头只有周一~周五）按「最后 7 列」的位置补齐 —— 不补的话那几天格子里的课会被
    // 静默丢掉（判据只看 bestLabels >= 4，补不补都不会出声）。
    // 推断出来的列要是已经被别的天占了就不猜，改成「认不出来」并进 warnings。
    var colOfDay = [0, -1, -1, -1, -1, -1, -1, -1];
    var unplaced = 0;
    for (var dd = 1; dd <= 7; dd++) {
        colOfDay[dd] = dayCol[dd];
        if (colOfDay[dd] >= 0) continue;
        var guess = offset + dd - 1;
        if (guess >= tableCols) guess = -1;
        for (var kk = 1; kk <= 7; kk++) {
            if (colOfDay[kk] === guess) guess = -1;
        }
        colOfDay[dd] = guess;
        if (guess < 0) unplaced++;
    }

    var order = [];
    var byCourse = {};

    for (var ri = 0; ri < rows.length; ri++) {
        if (ri === headerRow) continue;
        var row = rows[ri];
        var rowSection = rowSectionOfRow(row);
        for (var ci = 0; ci < row.length; ci++) {
            var cell = row[ci];
            var cellCol = colOf(cell, ci);
            var dayNo = 0;
            for (var d = 1; d <= 7; d++) {
                if (colOfDay[d] === cellCol) dayNo = d;
            }
            var parts = cell.parts || [];
            if (dayNo < 1) {
                // 有内容、却没有一天认领这一列：课就在眼前但排不进星期，出声不静默丢
                if (parts.length) unmappedBlocks++;
                continue;
            }
            for (var p = 0; p < parts.length; p++) {
                var blocks = String(parts[p]).split(DASHES);
                for (var b = 0; b < blocks.length; b++) {
                    var blockHtml = blocks[b];
                    if (plainText(blockHtml) === '') continue;
                    var name = nameOf(blockHtml);
                    var fonts = fontsOf(blockHtml);
                    var teachers = titleTexts(fonts, TEACHER_TITLE);
                    var rooms = titleTexts(fonts, ROOM_TITLE);
                    var specText = specTextOf(fonts);
                    // 节次：①「周次(节次)」里的方括号（强智通用）② 教室名里的方括号（上游的读法）
                    // ③ 所在行的节次标签。三个都没有就跳过并计数 —— 不静默丢课。
                    var section = sectionInBrackets(specText);
                    var fromRowLabel = false;
                    if (!section && rooms.length) section = sectionInBrackets(rooms[0]);
                    if (!section && rowSection) {
                        section = rowSection;
                        fromRowLabel = true;
                    }
                    var weeks = weeksIn(specText);
                    if (!name || !section || !weeks.length) {
                        skippedBlocks++;
                        continue;
                    }
                    if (fromRowLabel) sectionFromRow++;
                    var key = name + ' | ' + teachers.join(',');
                    var course = byCourse[key];
                    if (!course) {
                        course = {
                            name: name,
                            teacher: teachers.length ? teachers.join(',') : null,
                            note: null,
                            raw: []
                        };
                        byCourse[key] = course;
                        order.push(key);
                    }
                    var runs = runsOf(weeks);
                    for (var n = 0; n < runs.length; n++) {
                        var run = runs[n];
                        course.raw.push({
                            dayOfWeek: dayNo,
                            startPeriod: section.start,
                            endPeriod: section.end,
                            startWeek: run.start,
                            endWeek: run.end,
                            weekType: run.weekType,
                            location: rooms.length ? removeSection(rooms[0]) || null : null
                        });
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

    function weekKeyOf(block) {
        return block.startWeek + '-' + block.endWeek + '-' + block.weekType;
    }

    function byBlockOrder(a, b) {
        if (a.dayOfWeek !== b.dayOfWeek) return a.dayOfWeek - b.dayOfWeek;
        if (a.startPeriod !== b.startPeriod) return a.startPeriod - b.startPeriod;
        if (a.startWeek !== b.startWeek) return a.startWeek - b.startWeek;
        if (a.endWeek !== b.endWeek) return a.endWeek - b.endWeek;
        if (a.weekType !== b.weekType) return a.weekType < b.weekType ? -1 : 1;
        var left = a.location || '';
        var right = b.location || '';
        if (left !== right) return left < right ? -1 : 1;
        return 0;
    }

    // 上游的 mergeAndDistinctCourses()：同一天 + 同一周次 + 同一教室、节次相邻的两条合成一条
    //（1-2 节 + 3-4 节 → 1-4 节），完全相同的两条去重。上游是在全局排序后线性扫，这里按
    //「天 + 周次 + 教室」分组做，结果等价，但不依赖课程名/教师名的排序。
    function mergeRuns(raw) {
        var groups = {};
        var groupOrder = [];
        for (var i = 0; i < raw.length; i++) {
            var b = raw[i];
            var gk = b.dayOfWeek + '|' + weekKeyOf(b) + '|' + (b.location || '');
            if (!groups[gk]) {
                groups[gk] = [];
                groupOrder.push(gk);
            }
            groups[gk].push(b);
        }
        var out = [];
        for (var g = 0; g < groupOrder.length; g++) {
            var list = groups[groupOrder[g]];
            list.sort(function (a, b) {
                return (a.startPeriod - b.startPeriod) || (a.endPeriod - b.endPeriod);
            });
            var current = null;
            for (var k = 0; k < list.length; k++) {
                var item = list[k];
                if (current && item.startPeriod === current.startPeriod &&
                    item.endPeriod === current.endPeriod) {
                    continue; // 完全重复的一条
                }
                if (current && item.startPeriod === current.endPeriod + 1) {
                    current.endPeriod = item.endPeriod; // 连堂：接着上一节
                    continue;
                }
                if (current) out.push(current);
                current = {
                    dayOfWeek: item.dayOfWeek,
                    startPeriod: item.startPeriod,
                    endPeriod: item.endPeriod,
                    startWeek: item.startWeek,
                    endWeek: item.endWeek,
                    weekType: item.weekType,
                    location: item.location
                };
            }
            if (current) out.push(current);
        }
        out.sort(byBlockOrder);
        return out;
    }

    function tailPeriod(index) {
        for (var i = 0; i < TAIL_PERIOD_TIMES.length; i++) {
            if (TAIL_PERIOD_TIMES[i].periodIndex === index) {
                return { periodIndex: index, start: TAIL_PERIOD_TIMES[i].start, end: TAIL_PERIOD_TIMES[i].end };
            }
        }
        return null;
    }

    var maxWeek = 0;
    var maxPeriod = 0;
    var courses = [];
    for (var oi = 0; oi < order.length; oi++) {
        var item = byCourse[order[oi]];
        var blocks = mergeRuns(item.raw);
        for (var bi = 0; bi < blocks.length; bi++) {
            if (blocks[bi].endWeek > maxWeek) maxWeek = blocks[bi].endWeek;
            if (blocks[bi].endPeriod > maxPeriod) maxPeriod = blocks[bi].endPeriod;
        }
        courses.push({ name: item.name, teacher: item.teacher, note: item.note, blocks: blocks });
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    var periodTimes = PERIOD_TIMES.slice();
    var tableLimit = PERIOD_TIMES.length + TAIL_PERIOD_TIMES.length;
    if (maxPeriod > periodTimes.length) {
        for (var pi = periodTimes.length + 1; pi <= maxPeriod && pi <= tableLimit; pi++) {
            var tail = tailPeriod(pi);
            if (tail) periodTimes.push(tail);
        }
    }

    var termName = clean(term.name) || termNameFromCode(term.code);

    // extract.js 一定会带 now（提取时刻）；缺了就没法推算开学日，明确报错而不是瞎猜一个日期
    var firstDay = mondayOf(data.now);
    if (!firstDay) {
        throw new Error('提取数据里缺少 now（提取时刻），无法推算开学日期');
    }

    // 推算/假定出来的东西逐条说清楚（§4.2）：这些值在库里和真值长得一模一样，
    // 不说明用户就没有机会发现「现在第几周」是错的。
    var warnings = [
        '开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在「学期管理」里核对',
        '教务页面不提供学期总周数与作息时间：总周数按 ' + totalWeeks + ' 周、' +
            '节次时间按适配器内置的 10 节设定，请在「学期管理」里核对'
    ];
    if (totalWeeks > DEFAULT_TOTAL_WEEKS) {
        warnings.push(
            '课表里有排到第 ' + maxWeek + ' 周的课，学期总周数已从 ' + DEFAULT_TOTAL_WEEKS +
            ' 周抬高到 ' + totalWeeks + ' 周，请在「学期管理」里核对'
        );
    }
    if (maxPeriod > PERIOD_TIMES.length) {
        if (maxPeriod <= tableLimit) {
            warnings.push(
                '课表里有第 ' + (PERIOD_TIMES.length + 1) + ' 节及以后的课：适配器内置的作息表只到第 ' +
                PERIOD_TIMES.length + ' 节，多出来的节次时间取自空课的默认作息模板，请核对'
            );
        } else {
            warnings.push(
                '课表里有第 ' + (tableLimit + 1) +
                ' 节及以后的课，超出了适配器与空课默认作息表，这些课的时间需要你在「学期管理」里补'
            );
        }
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 条周次超出 ' + MAX_WEEK + ' 周，已按脏数据丢弃');
    }
    if (skippedBlocks > 0) {
        warnings.push(
            '有 ' + skippedBlocks + ' 个课程块没能解析出课程名、周次或节次，已跳过' +
            '（教务页面结构可能已调整，欢迎反馈给适配器）'
        );
    }
    if (sectionFromRow > 0) {
        warnings.push(
            '有 ' + sectionFromRow + ' 门课的课程块里没有节次，已按它所在行的节次标签填入，请核对'
        );
    }
    if (ambiguousWeeks > 0) {
        warnings.push(
            '有 ' + ambiguousWeeks + ' 处周次写法含「隔」字（如「隔周」），适配器看不出来，已按每周处理，请核对'
        );
    }
    if (unmappedBlocks > 0) {
        warnings.push(
            '有 ' + unmappedBlocks + ' 个课程块在课表里找不到对应的星期（它所在的列没有星期表头、' +
            '也不在推断出的最后 7 列内），这些课已跳过，请核对课表页面'
        );
    }
    if (bestLabels < 4) {
        warnings.push('课表里没找到星期表头，已按每行最后 7 列对应周一到周日，请核对导入结果');
    } else if (bestLabels < 7) {
        warnings.push(
            '课表的星期表头没有认全（只认出 ' + bestLabels + ' 天），其余的天已按「每行最后 7 列」' +
            '的位置推断' + (unplaced ? '，有 ' + unplaced + ' 天推断不出对应的列（那些列里的课已跳过）' : '') +
            '，请核对导入结果'
        );
    }
    if (!termName) {
        termName = '广西电力职业技术学院课表';
        warnings.push('没能识别出学年学期名称，学期名已用「广西电力职业技术学院课表」占位，请在「学期管理」里改名');
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
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
