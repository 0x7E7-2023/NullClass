(function () {
    // 重庆化工职业学院教务适配器（正方新版 jwglxt 平台）—— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 移植自 shiguang_warehouse 的 CQCIVC/cqcivc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链，去掉模板串、箭头函数与块级声明关键字）
    //   ② 不问用户「哪一学年 / 哪一学期」：用户在课表页时直接读页面上的 #xnm / #xqm
    //      （他自己切过的学期优先），否则取一次课表页 HTML 读同样两个下拉框的默认选中项 ——
    //      上游是弹窗让用户手输起始学年（还要用户自己算「2025-2026 输 2025」）再选学期
    //   ③ 只取数：排课行原样交出去，周次解析与课程合并全部挪到 parse.js（CI 里跑得到的那一段）
    //   ④ 两个域各管各的：登录走 CAS（dc.cqcivc.cn），课表接口在教务主机（jwxt.cqcivc.edu.cn）。
    //      登录全程由用户在 WebView 里手工完成，本脚本**只请求教务主机**，不碰 CAS 域
    //
    // 取数方式：两条请求，都打在本校教务主机 jwxt.cqcivc.edu.cn 上：
    //   ① GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default  读当前学年学期
    //      （用户已经开在课表页时跳过这一步，直接读页面上的 #xnm / #xqm）
    //   ② POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151                     取课表（JSON）
    // 上游只请求 ②，参数与上游逐字一致（xnm / xqm / kzlx / xsdm / kclbdm）。
    // 本脚本不读、不存、不上报任何账号信息，也不带走课表响应里夹带的学生信息。

    var JW_HOST = 'jwxt.cqcivc.edu.cn';
    var JWLGXT = '/jwglxt';
    var GNMKDM = 'N2151';

    // 课表接口在教务主机上；用户开在教务页面时就用当前 origin（http / https 与实际端口跟着页面走），
    // 否则按当前协议拼教务主机的绝对地址（登录页在 CAS 域上时是这一支）。
    // 两条分支都不写死 http/https：上游两个域给的地址都是 http，但学校若开了 https，
    // 写死 http 会在 https 页面上被 WebView 当混合内容拦掉。
    function jwOrigin() {
        var loc = window.location;
        var host = String(loc.hostname || '').toLowerCase();
        if (host === JW_HOST) {
            return loc.origin || (loc.protocol + '//' + loc.host);
        }
        return loc.protocol + '//' + JW_HOST;
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(url, options).then(function (response) {
            if (response.status < 200 || response.status >= 300) {
                throw new Error(
                    '教务系统返回 HTTP ' + response.status +
                    '：登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            return response.text();
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            throw new Error('连不上教务系统' + detail + '：请确认已登录，再点「提取课表」');
        });
    }

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    // 读一个学年学期字段：正常是下拉框（跳过 value 为空的占位项，被 selected 标记的那项优先，
    // 没标记就取第一项）；万一页面把它放在隐藏 input 里，就直接读 value（上游也是读 value 的）
    function readSelect(selectEl) {
        if (!selectEl) return null;
        var options = selectEl.options;
        if (!options && selectEl.querySelectorAll) options = selectEl.querySelectorAll('option');
        if (!options || !options.length) {
            var raw = String(selectEl.value === null || selectEl.value === undefined ? '' : selectEl.value)
                .replace(/\s+/g, '');
            return raw ? { value: raw, text: '' } : null;
        }
        var list = [];
        var picked = -1;
        var i;
        for (i = 0; i < options.length; i++) {
            var option = options[i];
            var value = String(option.value === null || option.value === undefined ? '' : option.value)
                .replace(/\s+/g, '');
            if (!value) continue;
            var label = String(option.textContent === null || option.textContent === undefined ? '' : option.textContent)
                .replace(/\s+/g, ' ').trim();
            if (option.selected && picked < 0) picked = list.length;
            list.push({ value: value, text: label });
        }
        if (!list.length) return null;
        if (picked < 0) picked = 0;
        return { value: list[picked].value, text: list[picked].text };
    }

    function readTermFromDoc(doc) {
        if (!doc || !doc.querySelector) return null;
        var year = readSelect(doc.querySelector('#xnm'));
        var season = readSelect(doc.querySelector('#xqm'));
        if (!year || !season) return null;
        return { xnm: year.value, xqm: season.value, xnmText: year.text, xqmText: season.text };
    }

    function termError() {
        return new Error(
            '没读到学年学期：' +
            '请先在教务系统里打开「信息查询 - 学生课表查询」，确认页面上能看到课表，再点「提取课表」'
        );
    }

    // 当前学年学期：优先读当前页面（用户自己在课表页切过的那个学期最准），
    // 读不到就取一次课表页 HTML 读同样两个下拉框。两条路都读不到就明确报错，
    // **不按日期猜** —— 猜错学期会导进一整个错学期的课，报错比猜错好。
    function currentTerm() {
        var onPage = readTermFromDoc(window.document);
        if (onPage) return Promise.resolve(onPage);
        if (typeof DOMParser === 'undefined') throw termError();
        return get(jwOrigin() + JWLGXT + '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' + GNMKDM + '&layout=default')
            .then(function (html) {
                var doc = new DOMParser().parseFromString(String(html), 'text/html');
                var term = readTermFromDoc(doc);
                if (!term) throw termError();
                return term;
            }, function () {
                // 取页面失败（未登录 / 该部署没有这个页面 / 用户不在教务域名下）时，
                // 说清楚该做什么，别把「读不到学年学期」伪装成网络故障
                throw termError();
            });
    }

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function localTodayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    function jsonOf(text) {
        try {
            return JSON.parse(String(text));
        } catch (e) {
            return null;
        }
    }

    // 课表接口（正方：POST 表单，xnm 学年 + xqm 学期代号）。请求体与上游逐字一致。
    // 这个接口一次性返回整学期的排课，没有分页参数；万一响应里带了记录总数，就一起交出去对账。
    function fetchTimetable(term) {
        var body = 'xnm=' + encodeURIComponent(term.xnm) +
            '&xqm=' + encodeURIComponent(term.xqm) +
            '&kzlx=ck&xsdm=&kclbdm=';
        return post(jwOrigin() + JWLGXT + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + GNMKDM, body)
            .then(function (text) {
                var json = jsonOf(text);
                if (!json || typeof json !== 'object' || !(json.kbList instanceof Array)) {
                    throw new Error(
                        '教务系统没有返回课表数据（返回的不是课表 JSON）：' +
                        '登录状态可能已失效，请重新登录后再点「提取课表」'
                    );
                }
                return json;
            });
    }

    function totalOf(json) {
        var keys = ['total', 'totalResult', 'totalCount', 'count'];
        for (var i = 0; i < keys.length; i++) {
            var value = json[keys[i]];
            if (typeof value === 'number' && isFinite(value) && value >= 0) return value;
            if (typeof value === 'string' && /^[0-9]+$/.test(value)) return parseInt(value, 10);
        }
        return null;
    }

    return currentTerm().then(function (term) {
        return fetchTimetable(term).then(function (json) {
            // 只交出排课行与实践课行；响应里夹带的学生信息（xsxx 等）一律不带出
            var rows = json.kbList;
            var practice = json.sjkList instanceof Array ? json.sjkList : [];
            return JSON.stringify({
                term: {
                    xnm: textOf(term.xnm),
                    xqm: textOf(term.xqm),
                    xnmText: textOf(term.xnmText),
                    xqmText: textOf(term.xqmText)
                },
                today: localTodayIso(),
                raw: { kbList: rows, sjkList: practice },
                total: totalOf(json)
            });
        });
    });
})()
