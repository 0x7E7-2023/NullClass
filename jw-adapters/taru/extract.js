(function () {
    // 塔里木大学教务适配器（正方新版 jwglxt 平台）—— 第一步：取数
    // 移植自 shiguang_warehouse 的 TARU/taru_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 本校的教务登录走学校统一的 CAS（auth.taru.edu.cn），课表本体在
    // jwgl.taru.edu.cn 的正方 jwglxt 上 —— 两者不同源。manifest 里 loginUrl 写 CAS
    // 入口、allowHosts 写课表主机，理由见 AUDIT.md §1。
    //
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链，去掉模板串、箭头函数）
    //   ② 不问用户「起始学年 + 第几学期」（上游 showPrompt + showSingleSelection）：
    //      用户已经在课表页时直接读页面上的 #xnm / #xqm（他自己切过的学期优先），
    //      不在课表页就取一次课表页 HTML，读离线文档里同样两个下拉框
    //   ③ 只取数：排课行（kbList）与节次时间原样交出去；周次解析、课程合并、开学日推算
    //      全部在 parse.js（CI 只跑得动 parse.js，逻辑放这里等于没有回归）
    //   ④ 不带出学生信息：课表响应里夹带的 xsxx（学号姓名等）一律不带走，也不进 fixture
    //   ⑤ 上游把作息时间写死两张表（夏季 / 非夏季）再让用户选一张；这里改成向教务自己的
    //      节次时间接口要（上游没有这一步）。取不到不算失败 —— 交给 parse.js 回落应用内置
    //      节次表并在 warnings 里说明。哪一套作息是「现在这一套」只有教务知道，
    //      脚本里挑一张就是猜
    //   ⑥ 上游只要有一行缺课名 / 教师 / 教室 / 星期 / 节次 / 周次就整行丢掉；这里原样交出，
    //      由 parse.js 按字段分别判断并写进 warnings（教师、教室本来就可以为空）
    //
    // 取数方式：同源接口，最多三个请求，全部打在本校教务主机 jwgl.taru.edu.cn 上：
    //   ① GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default  读当前学年学期
    //      （已经在课表页时这一步跳过）
    //   ② POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151                      取课表（JSON）
    //   ③ POST /jwglxt/kbcx/xskbcx_cxRjc.html?gnmkdm=N2151                         取节次时间（JSON 数组）
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var JWLGXT = '/jwglxt';
    var GNMKDM = 'N2151';
    // 课表主机：用户此刻不在教务系统里时用它兜底（正常路径下走的是当前页面同源，见 jwBase）
    var JW_ORIGIN = 'https://jwgl.taru.edu.cn';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 用户当前就在教务系统里（地址里带 /jwglxt/）时跟着当前地址走 —— 同源最稳，
    // 学校换域名或前面挂网关都不受影响；不在教务系统里才回落到固定的课表主机
    function jwBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(JWLGXT + '/');
        if (at >= 0) return originOf() + path.substring(0, at + JWLGXT.length);
        return JW_ORIGIN + JWLGXT;
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

    function jsonOf(source) {
        try {
            var parsed = JSON.parse(String(source));
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (error) {
            return null;
        }
    }

    // 读一个学年学期字段：正常是下拉框（跳过 value 为空的占位项，被 selected 标记的那项优先，
    // 没标记就取第一项）；万一页面把它放在隐藏 input 里，就直接读 value
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

    function currentTerm() {
        var onPage = readTermFromDoc(window.document);
        if (onPage) return Promise.resolve(onPage);
        if (typeof DOMParser === 'undefined') {
            throw new Error(
                '这个页面里读不到学年学期：请先在教务系统里打开「信息查询 - 学生课表查询」，再点「提取课表」'
            );
        }
        return get(jwBase() + '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' + GNMKDM + '&layout=default')
            .then(function (html) {
                var term = readTermFromDoc(new DOMParser().parseFromString(String(html), 'text/html'));
                if (!term) {
                    throw new Error(
                        '没读到学年学期：登录状态可能已失效，或教务系统改了课表页。' +
                        '请重新登录并打开「信息查询 - 学生课表查询」后再点「提取课表」'
                    );
                }
                return term;
            });
    }

    // 课表接口（正方：POST 表单，xnm 学年 + xqm 学期代号；与上游 taru_01.js 同一路径、同一 body）
    function fetchKbList(term) {
        var body = 'xnm=' + encodeURIComponent(term.xnm) + '&xqm=' + encodeURIComponent(term.xqm);
        return post(jwBase() + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + GNMKDM, body)
            .then(function (text) {
                var json = jsonOf(text);
                if (!json || !(json.kbList instanceof Array)) {
                    throw new Error(
                        '教务系统没有返回课表数据（返回的不是课表 JSON）：' +
                        '登录状态可能已失效，请重新登录后再点「提取课表」'
                    );
                }
                // 只交出排课行本身；响应里夹带的学生信息（xsxx 等）一律不带出
                return json;
            });
    }

    // 节次时间接口（正方按学期返回本校作息，数组，每条 { jcdm / jcmc, qssj, jssj }）。
    // 上游没有这一步（它写死两套作息让用户选），这里改成问教务要；取不到不影响导入。
    function fetchTimeSlots(term) {
        var body = 'xnm=' + encodeURIComponent(term.xnm) + '&xqm=' + encodeURIComponent(term.xqm);
        return post(jwBase() + '/kbcx/xskbcx_cxRjc.html?gnmkdm=' + GNMKDM, body)
            .then(function (text) {
                var json = jsonOf(text);
                if (json instanceof Array) return json;
                // 个别部署把数组包在 rows 之类的字段里
                if (json && json.rows instanceof Array) return json.rows;
                return null;
            })
            .catch(function () {
                return null;
            });
    }

    // 响应里如果带了记录总数，带出去给 parse.js 对账（这个接口本身不分页，
    // 总数对不上就说明教务端截断了，用户要能看见）
    function reportedTotalOf(json) {
        var keys = ['total', 'totalCount', 'totalNum', 'totalRows', 'recordCount', 'count'];
        for (var i = 0; i < keys.length; i++) {
            var value = json[keys[i]];
            if (typeof value === 'number' && value > 0 && Math.floor(value) === value) return value;
            if (typeof value === 'string' && /^[0-9]+$/.test(value.trim()) && parseInt(value, 10) > 0) {
                return parseInt(value, 10);
            }
        }
        return null;
    }

    function pad2(value) {
        return (value < 10 ? '0' : '') + value;
    }

    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    return currentTerm().then(function (term) {
        return fetchKbList(term).then(function (courseJson) {
            return fetchTimeSlots(term).then(function (slots) {
                return JSON.stringify({
                    term: {
                        xnm: term.xnm,
                        xqm: term.xqm,
                        xnmText: term.xnmText,
                        xqmText: term.xqmText
                    },
                    raw: { kbList: courseJson.kbList },
                    timeSlots: slots,
                    reportedTotal: reportedTotalOf(courseJson),
                    today: todayIso()
                });
            });
        });
    });
})()
