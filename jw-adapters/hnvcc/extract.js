(function () {
    // 湖南商务职业技术学院 教务适配器（湖南强智 · 学生端 /jsxsd/）—— 第一步：取数。
    //
    // 移植自 shiguang_warehouse 的 HNVCC/HNVCC_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //
    // 上游取数：GET http://jwxt.hnvcc.edu.cn/jsxsd/framework/mainV_index_loadkb.htmlx
    //              ?rq=all&xnxqid=<学年学期>&xswk=false
    //   —— 强智学生端「我的课表」面板的**服务端渲染片段**（返回一段 HTML，不是 JSON），
    //   上游再交给 DOMParser 解析 #timetable。上游 adapters.yaml 的 import_url 是
    //   http://jwxt.hnvcc.edu.cn/（强智学生端首页，课表面板就在那一页上）。
    //
    // 移植改动：
    //   ① 不写死主机名：请求**当前页面同源**的根相对路径，页面在哪台主机上就发给谁。
    //   ② 弹窗全删（上游三个：起始学年 showPrompt、第几学期与作息季节两个 showSingleSelection）。
    //      学年学期改从页面上「学年学期」下拉框取**选中的那一项**（用户切过学期就跟着他走；
    //      读不到就交给教务默认），作息季节交给 parse.js 按提取日期判定并写进 warnings。
    //   ③ 当前页面已经是课表页（表格里已经有课）就不发请求 —— 用户此刻看到的正是要导入的那张。
    //   ④ 只交**原始结构**出去：每行的网格列号（colspan/rowspan 都算过）+ 格子里每门课的
    //      原始文字（课名 / 教师 / 地点与周次那两个 span 的原文）。周次、节次、星期怎么解，
    //      全在 parse.js —— 那边 CI 里能用 Rhino 真跑。
    //
    // 请求只发往当前页面同源主机（该校是自己的教务主机）：不读账号密码、不写页面、
    // 不外发任何数据。审计见同目录 AUDIT.md。
    var KB_PATH = '/jsxsd/framework/mainV_index_loadkb.htmlx';
    var LOGIN_HINT = '请先在教务系统（http://jwxt.hnvcc.edu.cn/）登录，停在课表页后点「提取课表」';

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 提取时刻（本地日期，不能用 UTC：北京时间早上 8 点前 toISOString 会退到前一天）。
    // 只用于「教务给不出开学日时推算第 1 周」与「作息季节」—— 见 parse.js。
    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function tagOf(node) {
        return String((node && node.tagName) || '').toUpperCase();
    }

    // 一格里的课程明细。上游是：格子里每个 .item-box → 它的**直接子** <p> 是一门课（课名）
    // → 紧随其后的 .tch-name 给教师 → 再往后带 item1.png 图标的那个 div 的两个 span
    // 给地点与周次。这里只把原始文字抄出来（不做任何解释），并让「往后找」止步于下一个 <p>。
    // 上游在「找不到 .tch-name」或「找不到地点/周次 div」时**静默丢掉这门课**；
    // 这里照抄出来，由 parse.js 计数并进 warnings（不许静默丢课）。
    function boxesOf(cell) {
        var out = [];
        var boxes = cell.querySelectorAll ? cell.querySelectorAll('.item-box') : [];
        for (var b = 0; b < boxes.length; b++) {
            var kids = boxes[b].children || [];
            for (var k = 0; k < kids.length; k++) {
                if (tagOf(kids[k]) !== 'P') continue;
                var teacher = '';
                var spans = [];
                var node = kids[k].nextElementSibling;
                while (node && tagOf(node) !== 'P') {
                    if (!teacher && String(node.className || '').indexOf('tch-name') >= 0) {
                        var teacherSpans = node.getElementsByTagName('span');
                        teacher = clean(teacherSpans.length ? teacherSpans[0].textContent : node.textContent);
                    }
                    if (!spans.length) {
                        var images = node.getElementsByTagName ? node.getElementsByTagName('img') : [];
                        for (var im = 0; im < images.length; im++) {
                            var src = String(images[im].getAttribute('src') || '');
                            if (src.indexOf('item1.png') < 0) continue;
                            var infoSpans = node.getElementsByTagName('span');
                            for (var s = 0; s < infoSpans.length; s++) spans.push(clean(infoSpans[s].textContent));
                            break;
                        }
                    }
                    node = node.nextElementSibling;
                }
                out.push({ name: clean(kids[k].textContent), teacher: teacher, spans: spans });
            }
        }
        return out;
    }

    // 表格 → 带**网格列号**的行。colspan/rowspan 都算进去：parse.js 按列号对星期，
    // 不按「第几个格子」（上游是后者，首列不是节次列时整表错一天）。
    function gridOf(table) {
        var trs = table.getElementsByTagName('tr');
        var pending = {};
        var out = [];
        for (var r = 0; r < trs.length; r++) {
            var kids = trs[r].children || [];
            var cells = [];
            var col = 0;
            for (var c = 0; c < kids.length; c++) {
                var tag = tagOf(kids[c]);
                if (tag !== 'TD' && tag !== 'TH') continue;
                while (pending[col] > 0) col++;
                var colSpan = parseInt(kids[c].getAttribute('colspan'), 10);
                if (!(colSpan > 0)) colSpan = 1;
                var rowSpan = parseInt(kids[c].getAttribute('rowspan'), 10);
                if (!(rowSpan > 0)) rowSpan = 1;
                cells.push({
                    col: col,
                    colSpan: colSpan,
                    rowSpan: rowSpan,
                    text: clean(kids[c].textContent),
                    boxes: boxesOf(kids[c])
                });
                if (rowSpan > 1) pending[col] = rowSpan;
                col += colSpan;
            }
            out.push({ cells: cells });
            for (var key in pending) {
                if (pending[key] > 0) pending[key]--;
            }
        }
        return out;
    }

    function closestTable(el) {
        var node = el;
        while (node && node.nodeType === 1) {
            if (tagOf(node) === 'TABLE') return node;
            node = node.parentNode;
        }
        return null;
    }

    // 课表容器各校 id 不一：上游用 #timetable，别的强智部署见过 #kbtable，
    // 都没有就按「有 .item-box 格子的那张表」找。
    function findTable(doc) {
        var byId = doc.getElementById('timetable') || doc.getElementById('kbtable');
        if (byId) return byId;
        var box = doc.querySelector ? doc.querySelector('.item-box') : null;
        return box ? closestTable(box) : null;
    }

    // 学期总周数：上游读 #li_showWeek 里的「/20周」。只把**原始文字**交出去，怎么解在 parse.js。
    function totalWeeksText(doc) {
        var el = doc.getElementById('li_showWeek');
        return el ? clean(el.textContent) : '';
    }

    function cnNumber(value) {
        if (value === '一') return '1';
        if (value === '二') return '2';
        if (value === '三') return '3';
        return String(value);
    }

    // 兜底用的「学期名候选文字」：只从与学期有关的结构里取，不读整页 ——
    //   ① 页面标题（document.title）：强智写成「学生课表查询」这类页面名，不是个人信息；
    //   ② 页面上每个下拉框的 option 文本：选项是学期名这类固定词，不是自由文本；
    //   ③ id 或 class 里带 xnxq 的元素文字（强智对「学年学期」的命名），且只在文本很短时取。
    // 取到的整串文字只在本页内存里做一次正则匹配（见 readTerm），不保留、不进载荷、不外发。
    var HINT_MAX_LENGTH = 120;

    function termTextHints(doc) {
        var hints = [];
        var title = clean(doc.title);
        if (title) hints.push(title);
        var selects = doc.getElementsByTagName('select');
        var i;
        var j;
        for (i = 0; i < selects.length; i++) {
            var options = selects[i].options || [];
            for (j = 0; j < options.length; j++) {
                var optionText = clean(options[j].text);
                if (optionText) hints.push(optionText);
            }
        }
        var labels = doc.querySelectorAll ? doc.querySelectorAll('[id*="xnxq"], [class*="xnxq"]') : [];
        for (i = 0; i < labels.length; i++) {
            var labelText = clean(labels[i].textContent);
            if (labelText && labelText.length <= HINT_MAX_LENGTH) hints.push(labelText);
        }
        return hints;
    }

    // 学年学期：上游是弹窗让用户输「起始学年 + 第几学期」再拼成 2025-2026-1。
    // 这里改读页面上「学年学期」下拉框（强智是 select[id|name 含 xnxq]）里**选中**的那一项，
    // 值形如 2026-2027-1；读不到就用第一个可用的，仍然读不到就留空（交给教务默认）。
    function readTerm(doc) {
        var code = null;
        var name = null;
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length && !code; i++) {
            var identity = String(selects[i].id || '') + ' ' + String(selects[i].name || '');
            if (identity.indexOf('xnxq') < 0) continue;
            var options = selects[i].options || [];
            var firstCode = null;
            var firstName = null;
            for (var j = 0; j < options.length; j++) {
                var value = clean(options[j].value);
                if (!/^\d{4}-\d{4}-\d$/.test(value)) continue;
                if (firstCode === null) {
                    firstCode = value;
                    firstName = clean(options[j].text);
                }
                if (options[j].selected) {
                    code = value;
                    name = clean(options[j].text);
                }
            }
            if (!code && firstCode) {
                code = firstCode;
                name = firstName;
            }
        }
        if (!code) {
            // 下拉框认不出：在上面那几处「与学期有关」的文字里认「2026-2027学年第一学期」
            //（整串文字只做这一次匹配，只留下匹配到的那一小段当学期名）
            var hints = termTextHints(doc);
            for (var h = 0; h < hints.length && !code; h++) {
                var m = /(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期/.exec(hints[h]);
                if (!m) continue;
                code = m[1] + '-' + m[2] + '-' + cnNumber(m[3]);
                name = clean(m[0]);
            }
        }
        return { code: code, name: name };
    }

    function harvest(doc) {
        var table = findTable(doc);
        return {
            term: readTerm(doc),
            totalWeeksText: totalWeeksText(doc),
            rows: table ? gridOf(table) : []
        };
    }

    // 有课内容的课块数：判断这一页算不算「拿到了课表」
    function courseBoxes(rows) {
        var count = 0;
        for (var r = 0; r < rows.length; r++) {
            var cells = rows[r].cells || [];
            for (var c = 0; c < cells.length; c++) {
                if (cells[c].boxes && cells[c].boxes.length) count += cells[c].boxes.length;
            }
        }
        return count;
    }

    function payloadOf(flight) {
        return JSON.stringify({
            now: todayIso(),
            term: flight.term,
            totalWeeksText: flight.totalWeeksText || '',
            rows: flight.rows
        });
    }

    function htmlToDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    // 上游的请求一字未改：rq=all（整学期）、xswk=false、xnxqid 是学年学期编号。
    function urlFor(code) {
        return KB_PATH + '?rq=all&xnxqid=' + encodeURIComponent(code || '') + '&xswk=false';
    }

    function requestHtml(url) {
        return fetch(url, {
            method: 'GET',
            credentials: 'same-origin'
        }).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：' + LOGIN_HINT);
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回 HTTP ' + response.status + '：' + LOGIN_HINT);
            }
            return response.text();
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效。' + LOGIN_HINT);
        });
    }

    // ① 当前页面已经是课表页：一个请求都不发
    var live = harvest(document);
    if (courseBoxes(live.rows) > 0) return payloadOf(live);

    // ② 用页面上选中的学年学期请求同源课表片段（读不到就留空，按教务默认的当前学期）
    var asked = live.term.code || '';
    return requestHtml(urlFor(asked)).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (courseBoxes(got.rows) > 0) {
            return payloadOf({
                term: (got.term && got.term.code) ? got.term : live.term,
                totalWeeksText: got.totalWeeksText || live.totalWeeksText,
                rows: got.rows
            });
        }
        // ③ 第一次没带学期（或带错了）、而返回页自己给出了另一个学年学期：再请求一次（最多 2 个请求）
        if (!got.term.code || got.term.code === asked) {
            throw new Error('教务返回的课表是空的：可能本学期还没排课，或者登录状态已失效。' + LOGIN_HINT);
        }
        return requestHtml(urlFor(got.term.code)).then(function (again) {
            var flight = harvest(htmlToDoc(again));
            if (courseBoxes(flight.rows) === 0) {
                throw new Error('教务返回的课表是空的（' + got.term.code + '）：可能本学期还没排课，' +
                    '或者登录状态已失效。' + LOGIN_HINT);
            }
            return payloadOf({
                term: (flight.term && flight.term.code) ? flight.term : got.term,
                totalWeeksText: flight.totalWeeksText || got.totalWeeksText,
                rows: flight.rows
            });
        });
    });
})()
