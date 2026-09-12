(function () {
    // 广西电力职业技术学院（强智科技「高校综合管理教务系统」学生端 /jsxsd/）教务适配器
    // 移植自 shiguang_warehouse 的 GXDLXY/gxdlxy_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    // 上游 adapters.yaml：adapter_id GXDLXY_01、adapter_name「广西电力职业技术学院强智教务」、
    //   maintainer 星河欲转、import_url https://jw.gxdlxy.com/jsxsd/framework/xsMain.jsp。
    // 平台判定：上游请求 /jsxsd/xskb/xskb_list.do（强智学生端的课表页），与本校 hynu 同一平台；
    //   不是正方（正方新版是 /jwglxt/、课表接口 xskbcx_cxXsKb.html）。上游自称「强智教务」是对的。
    //   两校的相似度只有 0.739，差在哪些地方、同平台假设哪一层能复用，见 AUDIT.md。
    //
    // 取数方式：DOM（强智课表页返回的是服务端渲染的 HTML，不是 JSON）。三级兜底：
    //   ① 当前页面就有课表表格 → 直接用（用户此刻看到的那张，也不多发请求）
    //   ② GET  同源 /jsxsd/xskb/xskb_list.do   （教务默认的当前学期）
    //   ③ POST 同一地址，body 与上游一字不差（cj0701id=&zc=&demo=&xnxq01id=<学期>）
    //
    // 移植改动（与上游 gxdlxy_01.js 的逐条差别见 AUDIT.md）：
    //   ① 上游把课表地址写死成 https://jw.vpn.gxdlxy.com/jsxsd/xskb/xskb_list.do —— 那是学校
    //      WebVPN 网关的主机，而它自己 adapters.yaml 里的入口是 https://jw.gxdlxy.com/...，
    //      两个主机不是同一个；从入口页跨主机 fetch 是跨源请求（带 Cookie 的跨源 fetch 会被
    //      浏览器按 CORS 拦下），只有用户恰好把页面开在网关主机上才跑得通。
    //      这里改成**请求当前页面同源**，并把当前路径里 '/jsxsd/' 之前的前缀保留下来
    //      （WebVPN 会把地址重写成 /https-443/<站点哈希>/jsxsd/... 这种形态），
    //      于是直连与 WebVPN 两种开法共用同一份脚本，白名单里也不用写死主机。
    //   ② 不再弹窗问「起始学年 / 第几学期」：学年学期从课表页的「学年学期」下拉框里取，
    //      用户在页面上切学期，适配器就跟着他走（上游是手输年份 + 选第一/第二学期再拼 xnxq01id）。
    //   ③ 只交原始结构出去（学期 + 每一格的原始 HTML + 该格的列号/跨列数），周次、节次、
    //      课程名的解释全在 parse.js —— 上游把这些混在 DOM 解析里，而且用的是页面主文档的
    //      document.createElement（拿不到真实布局，CI 里也跑不到）。
    //   ④ 去掉上游的欢迎弹窗与一串 toast（空课的导入流程自己会确认），
    //      以及上游写在页面上的全局函数 window.validateYearInput。
    //
    // 只请求当前页面同源的 /jsxsd/xskb/xskb_list.do：不读账号密码、不写页面、不外发任何数据。
    var KB_SUFFIX = '/jsxsd/xskb/xskb_list.do';
    var LOGIN_HINT = '请先在学校入口登录教务系统并打开「课表查询」，确认页面上能看到课表再点提取';

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

    // 课表接口地址：与当前页面**同源**，并保留当前路径里 '/jsxsd/' 之前的前缀。
    //   直连            https://jw.gxdlxy.com/jsxsd/framework/xsMain.jsp
    //                   → /jsxsd/xskb/xskb_list.do
    //   WebVPN 网关     https://<网关>/https-443/<站点哈希>/jsxsd/xskb/xskb_list.do
    //                   → /https-443/<站点哈希>/jsxsd/xskb/xskb_list.do
    // 同平台的 BTBU 适配器也是这么推导的（社区里对着真实 WebVPN 抓过包）。
    function kbPath() {
        var path = '';
        try {
            path = String((window.location && window.location.pathname) || '');
        } catch (e) {
            path = '';
        }
        var at = path.search(/\/jsxsd\//i);
        if (at < 0) return KB_SUFFIX;
        return path.slice(0, at) + KB_SUFFIX;
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

    // col 是该格在本行里的**网格列号**（把 colspan 算进去），span 是跨列数。
    // 强智的课表首列常常是节次列，少数格子会跨列；只按「第几个格子」算星期会整表错位，
    // 所以把列号交给 parse.js，由它跟表头的星期标签对齐。
    function cellOf(node, col, span) {
        var text = node.textContent !== undefined ? node.textContent : node.innerText;
        return { text: clean(text), parts: partsOf(node), col: col, span: span };
    }

    function cellsOfRow(row) {
        var out = [];
        var kids = row.children || [];
        var col = 0;
        for (var i = 0; i < kids.length; i++) {
            var tag = tagOf(kids[i]);
            if (tag !== 'TD' && tag !== 'TH') continue;
            var span = parseInt(kids[i].getAttribute ? kids[i].getAttribute('colspan') : '', 10);
            if (!(span >= 1)) span = 1;
            out.push(cellOf(kids[i], col, span));
            col += span;
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

    // 课表容器的 id 各校不一（上游 GXDLXY 用 #kbtable，同平台的 hynu 用 #timetable），
    // 都没有就按「有 kbcontent 格子的那张表」找。
    function findTable(doc) {
        var byId = doc.getElementById('kbtable') || doc.getElementById('timetable');
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
    //   ③ id 或 class 里带 xnxq 的元素文字（强智对「学年学期」的命名），且只在文本很短时取。
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

    // 学年学期：课表页顶部的下拉框（强智是 select[id|name 含 xnxq]），取「选中」的那一项 ——
    // 用户在页面上切过学期，这里就跟着他走（不再弹窗问）。
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
    function postTerm(code) {
        return requestHtml(kbPath(), {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(code)
        });
    }

    var live = harvest(document);
    if (courseCells(live.rows) > 0) return payloadOf(live);

    return requestHtml(kbPath(), { method: 'GET', credentials: 'include' }).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (courseCells(got.rows) > 0) return payloadOf(got);
        if (!got.term.code) {
            throw new Error('教务页面里没找到课表，也没找到「学年学期」下拉框：' + LOGIN_HINT);
        }
        return postTerm(got.term.code).then(function (posted) {
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
