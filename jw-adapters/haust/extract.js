(function () {
    // 河南科技大学教务适配器（树维 EAMS 平台，/eams/，上海树维信息科技有限公司 / SupWisdom）—— 第一步：只取数。
    //
    // 移植自 shiguang_warehouse 的 HAUST/haust.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Haooz）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //   上游 adapters.yaml：adapter_name「河南科技大学树维教务」、maintainer「Haooz」、
    //   import_url「https://vpn.haust.edu.cn」、描述「需通过VPN访问，登录后进入课表页面点击导入」。
    //
    // 这一件在本批里是唯一的「接口取学期 + DOM 读课程」混合形态：
    //   · 全脚本只有一条网络请求 —— POST 同源相对路径 /eams/dataQuery.action，
    //     取学期列表（含起止日期）；上游的 /eams/courseTableForStd.action 那一路这里没有；
    //   · 课程从**已经渲染出来的课表页**上读（td[title] / td[id^='TD']），周次是**文本**不是位图。
    //
    // 移植改动：
    //   ① 【安全】上游把接口返回的正文丢进 eval（「eval ( "(" + text + ")" )」）来解析，
    //      命中移植手册 §5 第 6 条（不 eval 远程代码）。这里换成 JSON.parse；解析不了时退到
    //      一个**只读数据的容错扫描器**（允许单引号、无引号键名、尾逗号、// 与 /* */ 注释），
    //      全程不构造任何可执行代码，也不调用 eval / new Function（AUDIT.md §2 有专节）。
    //   ② 【安全】不弹窗、不写页面、不点页面、不读凭据：上游的 showAlert 开场说明与
    //      showSingleSelection 选学期全部删除（手册 §3 第 1 步：自动取当前学期）。
    //   ③ 只取数：把「课表格子（行列位置 + title + id + 文字）」「表头的星期与节次时间」
    //      「学期列表」原样交出去；课程名 / 教师 / 周次 / 节次的**全部解析挪到 parse.js**
    //      （CI 只跑得动 parse.js）。上游把读 DOM 与解析揉在一个自执行脚本里，这里按手册 §3 切开。
    //   ④ 上游只认 td[title]，格子没有 title 属性就整格丢掉。这里退回单元格的文字（上游其实也
    //      用它自己的 title 文本），并在找不到 td[title] 时用 td[id^='TD'] 兜底（上游有两套选择器
    //      但没有合并）。
    //   ⑤ 上游把 iframe 与 iframe 里的 iframe 都找了一遍：这里保留同样两级查找，
    //      但把「课表在第几层」如实交出去（source），不再只写一句「找到课表」。
    //   ⑥ 上游用 Array.from(row.cells) 取列号；这里不依赖 row.cells（iframe 里取出的文档
    //      在旧 WebView 上未必给全），改成按兄弟节点数 td/th 的个数算列号，不用 ES6。
    //   ⑦ ES6 → ES5：去掉了 async/await（改成 then 链）、箭头函数、模板串、const/let、
    //      Array.from、key.startsWith（改成手写的 indexOf === 0）。
    //   ⑧ 新增页面线索：表头里的星期名与「第N-M节 HH:mm-HH:mm」作息时间（两段必须相邻才算表头，
    //      课表格子正文里的「1-2节」与「08:00」不挨着就不会被当成作息来源）；一个标签吃多行
    //      （连堂）时按行分派节次；只写「第N节」没写时间的表头格也照实交出去（时间留空），
    //      由 parse.js 决定怎么处理并出声。教务不给作息表，这是唯一的来源；读不到就交空数组。
    //
    // 网络：只发一条同源相对路径请求（打到当前页面的主机上），不写死任何主机名。
    //   allowHosts 为空数组，理由见 AUDIT.md §2。

    var TERM_PATH = '/eams/dataQuery.action';
    var TERM_BODY = 'dataType=semesterCalendar&tagId=semesterBar&empty=true';
    var TERM_TIMEOUT_MS = 10000;
    var MAX_PERIODS = 40;
    var MAX_SAMPLE = 200;
    var DAY_RE = /星期([一二三四五六日天])/;
    var SECTION_RE = /第([0-9]{1,2})节/;
    var HEADER_SECTION_RE = /第([0-9]{1,2}(?:[-~—－–][0-9]{1,2})?)节([0-9]{1,2}:[0-9]{2})[-~—－–]([0-9]{1,2}:[0-9]{2})/;

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    // 空白压成一个空格（课程名里的空格要留着）
    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
    }

    // 空白全部去掉（节次表头的「第1节 08:00-08:45」可能被换行拆开）
    function squeeze(value) {
        return text(value).replace(/\s+/g, '');
    }

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
    }

    function todayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    function pageUrl() {
        try {
            return text(window.location ? window.location.href : '');
        } catch (e) {
            return '';
        }
    }

    // ---------------------------------------------------------------- 容错 JSON
    // 只把文本读成数据：不构造函数、不执行任何东西。上游那个 eval 能吃掉 AJAX 常见的那几种
    // 「像 JSON 但不是 JSON」的写法，所以这里用一个小扫描器把它们吃掉。
    function looseJson(source) {
        var s = text(source);
        var i = 0;

        function isSpace(ch) {
            return ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r';
        }

        function skip() {
            while (i < s.length) {
                var ch = s.charAt(i);
                if (isSpace(ch)) {
                    i++;
                    continue;
                }
                if (ch === '/' && s.charAt(i + 1) === '/') {
                    while (i < s.length && s.charAt(i) !== '\n') i++;
                    continue;
                }
                if (ch === '/' && s.charAt(i + 1) === '*') {
                    i += 2;
                    while (i < s.length && !(s.charAt(i) === '*' && s.charAt(i + 1) === '/')) i++;
                    i += 2;
                    continue;
                }
                break;
            }
        }

        function readString() {
            var quote = s.charAt(i);
            i++;
            var out = '';
            while (i < s.length) {
                var ch = s.charAt(i);
                if (ch === '\\') {
                    var esc = s.charAt(i + 1);
                    if (esc === 'n') out += '\n';
                    else if (esc === 't') out += '\t';
                    else if (esc === 'r') out += '\r';
                    else if (esc === 'u') {
                        out += String.fromCharCode(parseInt(s.substr(i + 2, 4), 16));
                        i += 4;
                    } else out += esc;
                    i += 2;
                    continue;
                }
                if (ch === quote) {
                    i++;
                    return out;
                }
                out += ch;
                i++;
            }
            throw new Error('字符串没有结束');
        }

        function readToken() {
            var start = i;
            while (i < s.length) {
                var ch = s.charAt(i);
                if (ch === ',' || ch === '}' || ch === ']' || ch === ':' || isSpace(ch)) break;
                i++;
            }
            var raw = s.substring(start, i);
            var lower = raw.toLowerCase();
            if (raw === '') throw new Error('空的值');
            if (lower === 'true') return true;
            if (lower === 'false') return false;
            if (lower === 'null') return null;
            if (lower === 'undefined') return undefined;
            if (/^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$/.test(raw)) return Number(raw);
            return raw;
        }

        function readValue() {
            skip();
            var ch = s.charAt(i);
            if (ch === '{') return readObject();
            if (ch === '[') return readArray();
            if (ch === '"' || ch === "'") return readString();
            return readToken();
        }

        function readObject() {
            var out = {};
            i++;
            skip();
            if (s.charAt(i) === '}') {
                i++;
                return out;
            }
            while (i < s.length) {
                skip();
                var key;
                var ch = s.charAt(i);
                if (ch === '"' || ch === "'") {
                    key = readString();
                } else {
                    var start = i;
                    while (i < s.length && s.charAt(i) !== ':' && !isSpace(s.charAt(i))) i++;
                    key = s.substring(start, i);
                }
                skip();
                if (s.charAt(i) !== ':') throw new Error('键后面不是冒号');
                i++;
                out[key] = readValue();
                skip();
                if (s.charAt(i) === ',') {
                    i++;
                    skip();
                    if (s.charAt(i) === '}') {
                        i++;
                        return out;
                    }
                    continue;
                }
                if (s.charAt(i) === '}') {
                    i++;
                    return out;
                }
                throw new Error('对象没有结束');
            }
            throw new Error('对象没有结束');
        }

        function readArray() {
            var out = [];
            i++;
            skip();
            if (s.charAt(i) === ']') {
                i++;
                return out;
            }
            while (i < s.length) {
                out.push(readValue());
                skip();
                if (s.charAt(i) === ',') {
                    i++;
                    skip();
                    if (s.charAt(i) === ']') {
                        i++;
                        return out;
                    }
                    continue;
                }
                if (s.charAt(i) === ']') {
                    i++;
                    return out;
                }
                throw new Error('数组没有结束');
            }
            throw new Error('数组没有结束');
        }

        skip();
        // 有些部署把响应包成「({"…":…})」这种圆括号表达式（eval 当年就是这么吃下的），
        // 这里手工剥一层；末尾的「)」与「;」也照剥。剥的是括号，不是执行任何东西。
        if (s.charAt(i) === '(') {
            i++;
            var wrapped = readValue();
            skip();
            if (s.charAt(i) === ')') i++;
            return wrapped;
        }
        return readValue();
    }

    function parseJsonText(raw) {
        try {
            return { ok: true, value: JSON.parse(text(raw)) };
        } catch (e) {
            // 不是严格 JSON：再试一次容错扫描器
        }
        try {
            return { ok: true, value: looseJson(raw) };
        } catch (e2) {
            return { ok: false, value: null };
        }
    }

    // ---------------------------------------------------------------- 学期接口
    function fetchTerms() {
        var init = {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: TERM_BODY
        };
        if (typeof fetch !== 'function') {
            return Promise.resolve({ ok: false, reason: '这个页面环境没有 fetch，取不到学期列表', raw: '' });
        }
        var request = fetch(TERM_PATH, init).then(function (response) {
            if (!response || response.status < 200 || response.status >= 300) {
                var status = response ? response.status : 0;
                return { ok: false, reason: '学期接口返回 HTTP ' + status + '（登录状态可能已失效）', raw: '' };
            }
            return response.text().then(function (body) {
                return { ok: true, body: text(body) };
            });
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            return { ok: false, reason: '学期接口连不上' + detail, raw: '' };
        });
        return new Promise(function (resolve) {
            var done = false;
            var timer = setTimeout(function () {
                if (done) return;
                done = true;
                resolve({ ok: false, reason: '学期接口超过 ' + (TERM_TIMEOUT_MS / 1000) + ' 秒没有响应', raw: '' });
            }, TERM_TIMEOUT_MS);
            request.then(function (result) {
                if (done) return;
                done = true;
                clearTimeout(timer);
                resolve(result);
            }, function (error) {
                if (done) return;
                done = true;
                clearTimeout(timer);
                var detail = error && error.message ? '（' + error.message + '）' : '';
                resolve({ ok: false, reason: '学期接口出错' + detail, raw: '' });
            });
        }).then(function (result) {
            if (!result.ok) return result;
            var parsed = parseJsonText(result.body);
            if (!parsed.ok || !parsed.value || typeof parsed.value !== 'object') {
                return {
                    ok: false,
                    reason: '学期接口返回的不是 JSON',
                    raw: result.body.substring(0, MAX_SAMPLE)
                };
            }
            var collected = collectSemesters(parsed.value.semesters);
            if (!collected.list.length) {
                return {
                    ok: false,
                    reason: '学期接口返回的数据里没有学期列表',
                    raw: result.body.substring(0, MAX_SAMPLE)
                };
            }
            return { ok: true, list: collected.list, groups: collected.groups };
        });
    }

    function semesterEntry(raw) {
        if (!raw || typeof raw !== 'object') return null;
        return {
            schoolYear: tidy(raw.schoolYear || raw.schoolYearName || ''),
            name: tidy(raw.name || raw.termName || ''),
            // 我们需要的只是这两个日期；接口里别的字段（学分制、小节数之类）一律不带出去
            startDate: tidy(raw.startDate || raw.start || ''),
            endDate: tidy(raw.endDate || raw.end || '')
        };
    }

    // data.semesters 的形状：上游按「键以 y 开头且值是数组」收集（一层）。
    // 这里放宽成「任何数组值的键都收」，并额外支持多一层嵌套（对象里再放数组）。
    function collectSemesters(semesters) {
        var list = [];
        var groups = [];
        if (!semesters || typeof semesters !== 'object') return { list: list, groups: groups };
        for (var key in semesters) {
            if (!Object.prototype.hasOwnProperty.call(semesters, key)) continue;
            var value = semesters[key];
            if (isArray(value)) {
                groups.push(text(key));
                for (var i = 0; i < value.length; i++) {
                    var entry = semesterEntry(value[i]);
                    if (entry && (entry.schoolYear || entry.name || entry.startDate)) list.push(entry);
                }
                continue;
            }
            if (value && typeof value === 'object') {
                for (var inner in value) {
                    if (!Object.prototype.hasOwnProperty.call(value, inner)) continue;
                    if (!isArray(value[inner])) continue;
                    groups.push(text(key) + '.' + text(inner));
                    for (var j = 0; j < value[inner].length; j++) {
                        var nested = semesterEntry(value[inner][j]);
                        if (nested && (nested.schoolYear || nested.name || nested.startDate)) list.push(nested);
                    }
                }
            }
        }
        return { list: list, groups: groups };
    }

    // ---------------------------------------------------------------- 课表 DOM
    function frameDocument(frame) {
        try {
            if (frame.contentDocument) return frame.contentDocument;
            if (frame.contentWindow) return frame.contentWindow.document;
        } catch (e) {
            // 跨域 iframe 读不到 document：跳过（只读，不会改任何东西）
        }
        return null;
    }

    // 一格里可能混着 textNode 与 <br>，textContent 出来是连在一起的文字；
    // 我们把它压成一个没有空白的串交出去（与上游读 title 的形态一致，parse.js 好处理）
    function cellText(node) {
        var raw = '';
        try {
            raw = node.textContent === null || node.textContent === undefined ? '' : String(node.textContent);
        } catch (e) {
            raw = '';
        }
        return squeeze(raw);
    }

    function classNameOf(node) {
        var value = node.className;
        return typeof value === 'string' ? value : '';
    }

    function rowIndexOf(node) {
        var index = 0;
        var sibling = node ? node.previousSibling : null;
        while (sibling) {
            if (sibling.nodeType === 1 && String(sibling.tagName).toUpperCase() === 'TR') index++;
            sibling = sibling.previousSibling;
        }
        return index;
    }

    function colIndexOf(row, node) {
        var index = 0;
        var sibling = node ? node.previousSibling : null;
        while (sibling) {
            if (sibling.nodeType === 1) {
                var tag = String(sibling.tagName).toUpperCase();
                if (tag === 'TD' || tag === 'TH') index++;
            }
            sibling = sibling.previousSibling;
        }
        return index;
    }

    // 表头上的两样东西：星期名（第几列是星期几）与节次时间（第N节 08:00-08:45）。
    // 教务不给作息表，这是唯一的来源；读不到就交空数组，parse.js 不瞎编。
    function pageHints(doc) {
        var days = [];
        var periods = [];
        var seenDay = {};
        var seenPeriod = {};
        var nodes;
        try {
            nodes = doc.querySelectorAll('td, th');
        } catch (e) {
            nodes = [];
        }
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var label = tidy(node.textContent);
            if (!label) continue;
            var rowSpan = node.rowSpan > 1 ? node.rowSpan : 1;
            var baseRow = rowIndexOf(node.parentNode);
            var dayMatch = DAY_RE.exec(label);
            if (dayMatch && !seenDay[dayMatch[1]]) {
                seenDay[dayMatch[1]] = true;
                days.push({ row: baseRow, col: colIndexOf(node.parentNode, node), text: dayMatch[0] });
            }
            var flat = squeeze(label);
            // 「第N节」/「第N-M节」后面紧跟「HH:mm-HH:mm」才算节次表头 —— 两段必须挨着。
            // 课表格子的正文里也常有「1-2节」和「08:00」，不挨着就不是节次表头，不能当作息来源。
            var sectionMatch = HEADER_SECTION_RE.exec(flat);
            if (sectionMatch) {
                var tokens = [];
                var pieces = sectionMatch[1].split(/[-~—－–]/);
                for (var t = 0; t < pieces.length; t++) {
                    var value = parseInt(pieces[t], 10);
                    if (value >= 1 && value <= MAX_PERIODS) tokens.push(value);
                }
                // 「第5-6节 15:00-16:35」说的是第 5 节与第 6 节同一段起止时间，
                // 所以两个节次都收；一个标签 rowSpan 吃多行时，第 offset 行取标签里的第 offset 个节次。
                for (var offset = 0; offset < rowSpan && offset < MAX_PERIODS; offset++) {
                    var index = offset < tokens.length
                        ? tokens[offset]
                        : tokens[tokens.length - 1] + (offset - tokens.length + 1);
                    if (!(index >= 1) || seenPeriod[index]) continue;
                    seenPeriod[index] = true;
                    periods.push({
                        index: index,
                        start: sectionMatch[2],
                        end: sectionMatch[3],
                        row: baseRow + offset,
                        col: colIndexOf(node.parentNode, node)
                    });
                }
            } else if (/^第[0-9]{1,2}节$/.test(flat)) {
                // 只有「第N节」没有时间的表头格：也交出去，parse.js 会如实说「这几节没有时间」
                var bare = SECTION_RE.exec(flat);
                if (bare && !seenPeriod[bare[1]]) {
                    seenPeriod[bare[1]] = true;
                    periods.push({
                        index: parseInt(bare[1], 10),
                        start: '',
                        end: '',
                        row: baseRow,
                        col: colIndexOf(node.parentNode, node)
                    });
                }
            }
            if (days.length >= 7 && periods.length >= MAX_PERIODS) break;
        }
        periods.sort(function (a, b) { return a.index - b.index; });
        return { days: days, periods: periods };
    }

    function scanDocument(doc, depth) {
        if (!doc || !doc.querySelectorAll) return null;
        var nodes;
        try {
            nodes = doc.querySelectorAll("td[title], td[id^='TD'], td.infoTitle");
        } catch (e) {
            return null;
        }
        var cells = [];
        var seen = {};
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var rowEl = node.parentNode;
            var rowIndex = rowIndexOf(rowEl);
            var colIndex = colIndexOf(rowEl, node);
            var key = rowIndex + ':' + colIndex;
            if (seen[key]) continue;
            var title = tidy(node.getAttribute ? node.getAttribute('title') : '');
            var body = cellText(node);
            if (!title && !body) continue;
            seen[key] = true;
            cells.push({
                row: rowIndex,
                col: colIndex,
                span: node.rowSpan > 1 ? node.rowSpan : 1,
                id: text(node.id),
                className: classNameOf(node),
                title: title,
                text: body
            });
        }
        if (!cells.length) return null;
        var hints = pageHints(doc);
        return { depth: depth, cells: cells, days: hints.days, periods: hints.periods };
    }

    // 课表可能在当前文档、iframe 里，也可能在 iframe 里的 iframe 里（上游找了两层，这里同样找两层）
    function findTable() {
        var found = scanDocument(document, 0);
        if (found) return { table: found, source: 'current' };
        var frames;
        try {
            frames = document.querySelectorAll('iframe');
        } catch (e) {
            frames = [];
        }
        for (var i = 0; i < frames.length; i++) {
            var doc = frameDocument(frames[i]);
            if (!doc) continue;
            found = scanDocument(doc, 1);
            if (found) return { table: found, source: 'iframe' };
            var innerFrames;
            try {
                innerFrames = doc.querySelectorAll('iframe');
            } catch (e2) {
                innerFrames = [];
            }
            for (var j = 0; j < innerFrames.length; j++) {
                var innerDoc = frameDocument(innerFrames[j]);
                if (!innerDoc) continue;
                found = scanDocument(innerDoc, 2);
                if (found) return { table: found, source: 'inner-iframe' };
            }
        }
        return { table: null, source: 'none' };
    }

    return fetchTerms().then(function (terms) {
        var located = findTable();
        var hints = located.table ? located.table : pageHints(document);
        return JSON.stringify({
            url: pageUrl(),
            title: text(document.title),
            today: todayIso(),
            // 课表是在当前文档里、还是在 iframe / iframe 里的 iframe 里（真机核对时一眼看出取错层）
            source: located.source,
            frames: located.table ? located.table.depth : 0,
            semesters: terms.ok ? terms.list : [],
            // 学期分组的原始键（上游按「以 y 开头」收，这里按「任何数组值的键」收）——
            // 只在学期没取到时有诊断价值，所以失败时才带出去
            semesterGroups: terms.ok ? [] : (terms.groups || []),
            semesterError: terms.ok ? null : text(terms.reason),
            semesterSample: terms.ok ? '' : text(terms.raw),
            days: hints.days || [],
            periods: hints.periods || [],
            cells: located.table ? located.table.cells : []
        });
    });
})()
