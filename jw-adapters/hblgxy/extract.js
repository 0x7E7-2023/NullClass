(function () {
    // 淮北理工学院教务适配器（湖南强智科技 · 高校综合管理教务系统，学生端 /jsxsd/）。
    // 移植自 shiguang_warehouse 的 HBLGXY/hblgxy_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    // 上游 adapters.yaml：import_url = https://a.hblgxy.edu.cn:5111/jsxsd/，maintainer 星河欲转。
    //   它与同批已移植的 hynu 是同一套脚本（除主机端口与作息表外逐字相同）：同一个
    //   /jsxsd/xskb/xskb_list.do 接口、同一套 #timetable / div.kbcontent /
    //   font[title=教师|周次(节次)|教室] 结构。上游自称「强智适配」是对的 —— 详见 AUDIT.md。
    //
    // 取数方式：DOM 抓取（强智课表页返回服务端渲染的整张 HTML 表格，不是 JSON）。
    // 三个来源，按代价从小到大依次尝试：
    //   ① 当前页面就有课表 → 直接用（用户在页面上切过学期，这里就跟着他走，也不多发请求）
    //   ② GET  /jsxsd/xskb/xskb_list.do   教务默认的当前学期
    //   ③ POST /jsxsd/xskb/xskb_list.do   带上学年学期（参数与上游一字不差；zc 留空 = 不按周过滤，
    //      这是本校唯一会造成「只拿到一部分课」的参数，取数时一律留空）
    // 这张表一次返回整学期，接口没有分页参数，也不回记录总数 —— 不存在「只取第一页」的问题。
    //
    // **非默认端口**：本校教务在 a.hblgxy.edu.cn:5111 上。桥的 origin 规则是
    // scheme://host:port 语义（见 JwOriginRules），manifest.loginUrl 里那个 :5111 就是桥注入的
    // 依据；而 allowHosts 按规范只写主机名、带不了端口。所以下面的请求地址优先拿**当前页面的
    // origin** 拼：用户从哪个 origin 登进来就从哪儿取数（学校换端口、走网关都不用改脚本），
    // 只有当前页不在本校主机上时才回落到写死的 https://a.hblgxy.edu.cn:5111。
    //
    // 只请求 a.hblgxy.edu.cn（同一台教务服务器）：不读账号密码、不写页面、不外发任何数据。
    var KB_PATH = '/jsxsd/xskb/xskb_list.do';
    var KB_HOST = 'a.hblgxy.edu.cn';
    var KB_FALLBACK = 'https://a.hblgxy.edu.cn:5111' + KB_PATH;
    var LOGIN_HINT = '请先在页面里登录教务系统（https://a.hblgxy.edu.cn:5111/jsxsd/），' +
        '确认能看到「课表查询」页面再点提取';

    function originOf() {
        var loc = window.location;
        if (!loc) return '';
        var origin = loc.origin;
        if (!origin && loc.protocol) origin = String(loc.protocol) + '//' + String(loc.host);
        return String(origin || '');
    }

    // 当前页就在本校教务上（同主机，端口随页面）→ 用它的 origin；否则回落写死的地址。
    function kbUrl() {
        var origin = originOf().toLowerCase();
        if (/^https?:\/\/a\.hblgxy\.edu\.cn(:\d+)?$/.test(origin)) return origin + KB_PATH;
        return KB_FALLBACK;
    }

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 提取时刻（ISO 日期）。只用于教务页面给不出开学日时推算第 1 周 —— 见 parse.js。
    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function tagOf(node) {
        return String((node && node.tagName) || '').toUpperCase();
    }

    // 一格里的「明细」：强智把课程名 + 教师/周次/节次/教室一起放在 class 含 kbcontent 的 div 里，
    // 旁边那份可见的 div 是同一门课的简写。先取带 display:none 的那份（上游用的就是这个），
    // 没有隐藏的就取第一份 —— 只取一份，避免同一门课被算两次。
    function partsOf(cell) {
        var divs = cell.getElementsByTagName('div');
        var hidden = [];
        var any = [];
        for (var i = 0; i < divs.length; i++) {
            if (String(divs[i].className || '').indexOf('kbcontent') < 0) continue;
            any.push(divs[i]);
            var style = divs[i].getAttribute ? String(divs[i].getAttribute('style') || '') : '';
            if (style.indexOf('none') >= 0) hidden.push(divs[i]);
        }
        var picked = hidden.length ? hidden : (any.length ? [any[0]] : []);
        var parts = [];
        for (var j = 0; j < picked.length; j++) {
            var html = String(picked[j].innerHTML || '').trim();
            if (html && html !== '&nbsp;') parts.push(html);
        }
        return parts;
    }

    function cellOf(cell) {
        var text = cell.textContent !== undefined ? cell.textContent : cell.innerText;
        return { text: clean(text), parts: partsOf(cell) };
    }

    function cellsOfRow(row) {
        var out = [];
        var kids = row.children || [];
        for (var i = 0; i < kids.length; i++) {
            var tag = tagOf(kids[i]);
            if (tag === 'TD' || tag === 'TH') out.push(cellOf(kids[i]));
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

    // 课表容器的 id 各校不一（上游用 #timetable，其它强智学校见过 #kbtable），
    // 都没有就按「有 kbcontent 格子的那张表」找。
    function findTable(doc) {
        var byId = doc.getElementById('timetable') || doc.getElementById('kbtable');
        if (byId) return byId;
        var cell = doc.querySelector ? doc.querySelector('.kbcontent') : null;
        return cell ? closestTable(cell) : null;
    }

    function rowsOfTable(table) {
        var out = [];
        var trs = table.rows || table.getElementsByTagName('tr');
        for (var i = 0; i < trs.length; i++) {
            var cells = cellsOfRow(trs[i]);
            for (var c = 0; c < cells.length; c++) {
                if (cells[c].text || cells[c].parts.length) {
                    out.push(cells);
                    break;
                }
            }
        }
        return out;
    }

    function cnNumber(value) {
        if (value === '一') return '1';
        if (value === '二') return '2';
        if (value === '三') return '3';
        return String(value);
    }

    // 兜底用的「学期名候选文字」：只从与学期有关的结构里取，不读整页 ——
    //   ① 页面标题（document.title）：强智写成「学生课表查询」这类页面名，不是个人信息；
    //   ② 页面上每个下拉框的 option 文本：选项是学期名 / 课表类型这类固定词，不是自由文本；
    //   ③ id 或 class 里带 xnxq 的元素文字（强智对「学年学期」的命名），且只在文本很短时取
    //      —— 万一命中的是个包着整页的容器，超长的直接不当候选。
    // 取到的整串文字只在本页内存里做一次正则匹配（见 readTerm），不保留、不进载荷、不外发。
    var HINT_MAX_LENGTH = 120;

    function termTextHints(doc) {
        var hints = [];
        var title = clean(doc.title);
        if (title) hints.push(title);
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length; i++) {
            var options = selects[i].options || [];
            for (var j = 0; j < options.length; j++) {
                var optionText = clean(options[j].text);
                if (optionText) hints.push(optionText);
            }
        }
        var labels = doc.querySelectorAll
            ? doc.querySelectorAll('[id*="xnxq"], [class*="xnxq"]')
            : [];
        for (var k = 0; k < labels.length; k++) {
            var labelText = clean(labels[k].textContent);
            if (labelText && labelText.length <= HINT_MAX_LENGTH) hints.push(labelText);
        }
        return hints;
    }

    // 学年学期：课表页顶部的下拉框（强智是 select[id|name 含 xnxq]），
    // 取「选中」的那一项 —— 用户在页面上切过学期，这里就跟着他走（不再弹窗问）。
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
            rows: table ? rowsOfTable(table) : []
        };
    }

    // 有课内容的格子数：判断这一页算不算「拿到了课表」
    function courseCells(rows) {
        var count = 0;
        for (var r = 0; r < rows.length; r++) {
            for (var c = 0; c < rows[r].length; c++) {
                if (rows[r][c].parts.length) count++;
            }
        }
        return count;
    }

    function payloadOf(flight) {
        return JSON.stringify({
            now: todayIso(),
            term: flight.term,
            rows: flight.rows
        });
    }

    function requestHtml(url, init) {
        return fetch(url, init).then(function (response) {
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

    function htmlToDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    // 上游的请求体，一字未改（cj0701id 是班级、zc 是周次，两个都留空 = 全部）
    function postTerm(url, code) {
        return requestHtml(url, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(code)
        });
    }

    var url = kbUrl();
    var live = harvest(document);
    if (courseCells(live.rows) > 0) return payloadOf(live);

    return requestHtml(url, { method: 'GET', credentials: 'include' }).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (courseCells(got.rows) > 0) return payloadOf(got);
        if (!got.term.code) {
            throw new Error('教务页面里没找到课表，也没找到学年学期下拉框：' + LOGIN_HINT);
        }
        return postTerm(url, got.term.code).then(function (posted) {
            var again = harvest(htmlToDoc(posted));
            if (courseCells(again.rows) === 0) {
                throw new Error(
                    '教务返回的课表是空的（' + got.term.code + '）：可能本学期还没排课，' +
                    '或者登录状态已失效。' + LOGIN_HINT
                );
            }
            return payloadOf({
                term: (again.term && again.term.code) ? again.term : got.term,
                rows: again.rows
            });
        });
    });
})()
