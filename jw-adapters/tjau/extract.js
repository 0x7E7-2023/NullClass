(function () {
    // 天津农学院教务适配器（树维 EAMS 平台，jwxt.tjau.edu.cn/eams）—— 第一步：只取数，
    // 把教务的原始数据（学期日历原文 + 课表 HTML 全文）原样交出去。
    //
    // 移植自 shiguang_warehouse 的 TJAU/tjau.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 平台是**树维 EAMS**（/eams/，上海树维信息科技有限公司 SupWisdom，新开普子公司），不是强智。
    // ⚠️ 本件是同族 xatu / hfnu 的**克隆链源头**：上游 XATU/myschool.js 的文件头自己写着
    //    「基于天津农学院适配脚本」，HFNU/hfnu.js 与它逐字同构。三件的解析逻辑一模一样，
    //    只有主机、作息表与提示语不同 —— 所以本件的用例期望值全部按 tjau **自己的**作息表
    //    （第 1 节 08:30-09:15）与主机独立推出，一条也没有照抄邻件（见 AUDIT.md §5）。
    //
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链，去掉模板串、箭头函数与块级声明关键字）
    //   ② 三条请求的地址不再写死 http://jwxt.tjau.edu.cn（上游用的绝对 URL）：改成按当前页面的
    //      origin + 上下文路径 /eams 拼。走 http 还是 https、有没有挂在学校门户 / WebVPN 前缀下，
    //      由用户实际打开的那个地址决定，脚本不替教务系统做主；这样请求主机只剩 loginUrl 那一个
    //      （见 AUDIT.md §1），allowHosts 留空
    //   ③ 不再弹「选择学期」：上游用 showSingleSelection 让用户选，移植件自动取当前学期
    //      （学期日历里的当前学期 id → 学期起止日期包含今天的那一个 → 起始日期最晚的那一个），
    //      选中的依据原样交给 parse.js 写进 warnings。要导入别的学期，用户在教务页面里切一下
    //      再点「提取课表」即可（移植手册 §3 第 1 步）
    //   ④ 读学期日历不再用 Function("return (" + raw + ")")()（上游这样写等同 eval，响应体来自网络，
    //      命中移植手册 §5 第 6 条）：改成本文件里那组只扫值、不求值的字面量解析函数
    //   ⑤ 只取数：TaskActivity 解析、位图周次、课程合并全部挪到 parse.js（CI 只跑得到那一段）。
    //      本文件交出去的是**课表 HTML 全文**与**学期日历原文**，一个字都不解析成课程
    //   ⑥ 上游的 showToast / notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots
    //      这些桥调用全部没有移植；作息表也不在这里推
    //   ⑦ 学生 id（上游从课表页的 bg.form.addInput(form,"ids",...) 里取）**只用于发这一条课表请求，
    //      不写进输出**：输出里没有任何学号、姓名、身份信息
    //
    // 取数方式：同源请求，三条，全部打在本校教务主机上（地址都由 window.location.origin 拼出来）：
    //   ① GET  /eams/courseTableForStd.action?sf_request_type=ajax                 读学号 ids 与学期组件 id
    //   ② POST /eams/dataQuery.action?sf_request_type=ajax                        body: tagId=..&dataType=semesterCalendar
    //   ③ POST /eams/courseTableForStd!courseTable.action?sf_request_type=ajax    body: ignoreHead=1&setting.kind=std&semester.id=..&ids=..
    // 请求体与上游 tjau.js 逐字一致（上游不带 startWeek=，本件也不带）。
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var EAMS = '/eams';
    var MAX_RESPONSE_CHARS = 4000000;
    var BACKSLASH = String.fromCharCode(92);

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维 EAMS 的上下文路径是 /eams；万一挂在前缀下（门户 / 网关），跟着当前地址里的那一段走
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function trimOf(value) {
        return textOf(value).replace(/^\s+|\s+$/g, '');
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
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
        }).then(function (text) {
            var value = textOf(text);
            if (value.length > MAX_RESPONSE_CHARS) {
                throw new Error(
                    '教务系统的响应异常大（' + value.length + ' 字符），已停止以免交出被截断的数据；' +
                    '请确认已经登录、并开着课表页时再点「提取课表」'
                );
            }
            return value;
        });
    }

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    // 页面还在加载时先等一下（课表页把学期组件写在 HTML 里，取早了会读不到）
    function whenReady() {
        return new Promise(function (resolve) {
            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                resolve();
                return;
            }
            var done = false;
            var finish = function () {
                if (done) return;
                done = true;
                resolve();
            };
            if (document.addEventListener) document.addEventListener('DOMContentLoaded', finish);
            setTimeout(finish, 8000);
        });
    }

    // ---------- 极简字面量解析（只扫值，不用 eval / new Function） ----------
    // 教务的学期日历响应体是 JS 对象字面量：
    //   {semesters:{"1":[{id:454,name:"1",schoolYear:"2026-2027",startDate:"2026-08-31",...}],...},semesterId:454}
    // 上游用 Function("return (" + raw + ")")() 求值；这里改成下面这组「按顶层逗号/冒号切分 + 深度与引号
    // 感知」的函数。它不做任何求值，也不执行响应体里的任何内容。
    function splitTop(text, sep) {
        var parts = [];
        var current = '';
        var depth = 0;
        var quote = '';
        var i;
        for (i = 0; i < text.length; i++) {
            var ch = text.charAt(i);
            if (quote) {
                current += ch;
                if (ch === quote && text.charAt(i - 1) !== BACKSLASH) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") {
                quote = ch;
                current += ch;
                continue;
            }
            if (ch === '(' || ch === '[' || ch === '{') depth++;
            if (ch === ')' || ch === ']' || ch === '}') depth--;
            if (ch === sep && depth === 0) {
                parts.push(current);
                current = '';
                continue;
            }
            current += ch;
        }
        parts.push(current);
        return parts;
    }

    // 取最外层 {...} 或 [...] 之间的内容（括号配对、尊重引号与转义）
    function outerOf(text, open, close) {
        var start = text.indexOf(open);
        if (start < 0) return null;
        var depth = 0;
        var quote = '';
        var i;
        for (i = start; i < text.length; i++) {
            var ch = text.charAt(i);
            if (quote) {
                if (ch === quote && text.charAt(i - 1) !== BACKSLASH) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") {
                quote = ch;
                continue;
            }
            if (ch === open) {
                depth++;
            } else if (ch === close) {
                depth--;
                if (depth === 0) return text.substring(start + 1, i);
            }
        }
        return null;
    }

    function unquote(raw) {
        var s = trimOf(raw);
        if (s.length >= 2) {
            var first = s.charAt(0);
            if ((first === '"' || first === "'") && s.charAt(s.length - 1) === first) {
                return s.substring(1, s.length - 1);
            }
        }
        return s;
    }

    function literalOf(raw) {
        var s = trimOf(raw);
        if (!s) return null;
        var first = s.charAt(0);
        if (first === '{') return objectOf(s);
        if (first === '[') return arrayOf(s);
        if (first === '"' || first === "'") return unquote(s);
        if (s === 'null' || s === 'undefined') return null;
        if (/^-?[0-9]+(\.[0-9]+)?$/.test(s)) return Number(s);
        return s;
    }

    function objectOf(text) {
        var inner = outerOf(text, '{', '}');
        if (inner === null) return null;
        var pairs = splitTop(inner, ',');
        var out = {};
        var i;
        for (i = 0; i < pairs.length; i++) {
            if (!trimOf(pairs[i])) continue;
            var kv = splitTop(pairs[i], ':');
            if (kv.length < 2) continue;
            out[unquote(kv[0])] = literalOf(kv.slice(1).join(':'));
        }
        return out;
    }

    function arrayOf(text) {
        var inner = outerOf(text, '[', ']');
        if (inner === null) return [];
        var items = splitTop(inner, ',');
        var out = [];
        var i;
        for (i = 0; i < items.length; i++) {
            if (!trimOf(items[i])) continue;
            out.push(literalOf(items[i]));
        }
        return out;
    }

    function isoOfAny(value) {
        var s = textOf(value);
        var m = /(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/.exec(s);
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    function semesterOf(item) {
        return {
            id: String(item.id),
            rawName: textOf(item.name),
            schoolYear: textOf(item.schoolYear),
            startDate: isoOfAny(item.startDate),
            endDate: isoOfAny(item.endDate)
        };
    }

    function calendarOf(raw) {
        var result = { currentId: null, semesters: [] };
        var text = trimOf(raw);
        if (!text) return result;
        var root = objectOf(text);
        if (!root) return result;
        if (root.semesterId !== null && root.semesterId !== undefined && root.semesterId !== '') {
            result.currentId = String(root.semesterId);
        }
        var buckets = root.semesters;
        if (!buckets || typeof buckets !== 'object') return result;
        var key;
        for (key in buckets) {
            if (!Object.prototype.hasOwnProperty.call(buckets, key)) continue;
            var list = buckets[key];
            if (!(list instanceof Array)) continue;
            var i;
            for (i = 0; i < list.length; i++) {
                var item = list[i];
                if (!item || typeof item !== 'object') continue;
                if (item.id === null || item.id === undefined || item.id === '') continue;
                result.semesters.push(semesterOf(item));
            }
        }
        return result;
    }

    // 选学期：① 学期日历标出的当前学期 ② 起止日期包含今天的那一个 ③ 起始日期最晚的那一个。
    // 都不是「问用户」；返回里带上选中的依据（source），parse.js 会把它写进 warnings。
    function chose(item, source) {
        return {
            id: String(item.id),
            rawName: textOf(item.rawName),
            schoolYear: textOf(item.schoolYear),
            startDate: item.startDate,
            endDate: item.endDate,
            source: source
        };
    }

    function chooseSemester(cal, pageSemesterId, today) {
        var list = cal.semesters;
        var wanted = cal.currentId || pageSemesterId || null;
        var i;
        if (wanted) {
            for (i = 0; i < list.length; i++) {
                if (String(list[i].id) === String(wanted)) return chose(list[i], 'current');
            }
        }
        for (i = 0; i < list.length; i++) {
            var item = list[i];
            if (item.startDate && item.endDate && item.startDate <= today && today <= item.endDate) {
                return chose(item, 'date');
            }
        }
        var latest = null;
        for (i = 0; i < list.length; i++) {
            if (!list[i].startDate) continue;
            if (!latest || list[i].startDate > latest.startDate) latest = list[i];
        }
        if (latest) return chose(latest, 'latest');
        if (list.length) return chose(list[list.length - 1], 'last');
        // 学期列表空（接口没返回 / 被挡）：页面上的学期 id 还能用来发课表请求，
        // 只是拿不到学期名与起止日期，parse.js 会按列表为空的样子处理并写进 warnings
        if (wanted) {
            return { id: String(wanted), rawName: '', schoolYear: '', startDate: null, endDate: null, source: 'page' };
        }
        return null;
    }

    // 课表页 HTML 里的两个参数：学号 ids 与学期组件 id（上游同款正则，放宽空白）
    function paramsOf(html) {
        var idsMatch = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["'](\d+)["']\s*\)/.exec(html);
        var tagMatch = /id=["'](semesterBar\d+Semester)["']/.exec(html);
        if (!idsMatch || !tagMatch) return null;
        var tagId = tagMatch[1];
        // 学期组件元素自带当前的学期 id（value="454"），学期日历拿不到当前学期时用它兜底。
        // 用 indexOf 定位而不是把 tagId 拼进正则：tagId 是页面上的字符串，能不拼正则就不拼
        var at = html.indexOf('id="' + tagId + '"');
        if (at < 0) at = html.indexOf("id='" + tagId + "'");
        var semesterId = null;
        if (at >= 0) {
            var near = html.substring(at, at + 400);
            var valueMatch = /value\s*=\s*["']?(\d+)["']?/.exec(near);
            if (valueMatch) semesterId = valueMatch[1];
        }
        return {
            ids: idsMatch[1],
            tagId: tagId,
            semesterId: semesterId
        };
    }

    // 页面自己的 var unitCount（用户已经开在课表页时能读到），只在课表响应里没有时兜底
    function readUnitCountFromPage() {
        try {
            var html = document.documentElement ? textOf(document.documentElement.innerHTML) : '';
            var m = /(?:var\s+)?unitCount\s*=\s*(\d{1,3})/.exec(html);
            if (!m) return null;
            var count = parseInt(m[1], 10);
            return count > 0 ? count : null;
        } catch (e) {
            return null;
        }
    }

    return whenReady().then(function () {
        return get(eamsBase() + '/courseTableForStd.action?sf_request_type=ajax').then(function (entryHtml) {
            var params = paramsOf(entryHtml);
            if (!params) {
                throw new Error(
                    '未能识别教务参数（学号 / 学期组件），请确认已经登录教务系统，' +
                    '并在课表页点上「提取课表」'
                );
            }
            var calendarBody = 'tagId=' + encodeURIComponent(params.tagId) + '&dataType=semesterCalendar';
            return post(eamsBase() + '/dataQuery.action?sf_request_type=ajax', calendarBody)
                .then(function (calendarText) {
                    var chosen = chooseSemester(calendarOf(calendarText), params.semesterId, todayIso());
                    if (!chosen) {
                        throw new Error(
                            '没能确定要导入哪个学期（教务的学期列表为空，页面里也没有学期 id）：' +
                            '请重新登录后再点「提取课表」'
                        );
                    }
                    var tableBody = 'ignoreHead=1&setting.kind=std&semester.id=' + encodeURIComponent(chosen.id) +
                        '&ids=' + encodeURIComponent(params.ids);
                    return post(
                        eamsBase() + '/courseTableForStd!courseTable.action?sf_request_type=ajax',
                        tableBody
                    ).then(function (courseHtml) {
                        return JSON.stringify({
                            today: todayIso(),
                            semester: chosen,
                            unitCountPage: readUnitCountFromPage(),
                            semesterCalendar: calendarText,
                            courseTable: courseHtml
                        });
                    });
                });
        });
    });
})()
