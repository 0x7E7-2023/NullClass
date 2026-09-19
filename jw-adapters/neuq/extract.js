(function () {
    // 东北大学秦皇岛分校 教务适配器（树维 EAMS 平台，jwxt.neuq.edu.cn/eams）—— 第一步：只取数。
    //
    // 移植自 shiguang_warehouse 的 NEUQ/neuq.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 aryunm）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // ⚠️ 这不是东北大学主校区的适配器。主校区（「jwxt.neu.edu.cn」）走**金智 jwapp**，
    //    已合并的「jw-adapters/neu/」是那一套；秦皇岛分校走**树维 EAMS**，
    //    两套系统只是学校名像，取数链路、周次编码、作息表都不一样，**别混用**。
    //    「/eams/」是上海树维信息科技有限公司（SupWisdom，新开普子公司）的产品，
    //    不是湖南强智科技（强智走「/jsxsd/」）。
    //
    // 上游这条链路的原话：「课表以空 HTML 表格返回，课程数据通过 JavaScript 脚本动态注入」，
    // 所以本文件只负责把三段原始文本取回来（探测页 HTML、学期响应原文、课表 HTML 原文），
    // 从课表 HTML 里抽 TaskActivity、算周次、拼课程全部在 parse.js 里（CI 用 Rhino 真跑的那一段）。
    //
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链；去掉模板串、箭头函数、块级声明关键字、扩展运算符）
    //   ② 地址不再写死「https://jwxt.neuq.edu.cn」（上游 BASE 常量）：改成按**当前页面的** origin +
    //      上下文路径拼（「/eams」挂在前缀下时跟着走，例如门户 / WebVPN 网关重写过路径的情况）。
    //      于是请求主机只剩用户当前所在的那一个教务主机 —— allowHosts 留空，见 AUDIT.md §3
    //   ③ 上游拿探测页的「ids」当请求参数（「ids=<学号>」）。学号是个人身份字段，**不进输出**：
    //      这里只把探测页 HTML 原样交出去，由 parse.js 就地正则读，输出里不带学号
    //      （fixture 因此天然脱敏）
    //   ④ 学期改成**自动取当前学期**，不再弹「showSingleSelection」让用户选（手册 §3 第 1 步）。
    //      学期列表原样交出去，选择逻辑（学期选择框当前值 → 当前日期 → 最近一个已开始的学期）
    //      放在 parse.js，CI 验得到
    //   ⑤ 三个接口全部改成**取不到就降级**，不因为附加接口挂掉就整包失败：
    //      探测页 → 备选探测页；学期列表 → 交空串；课表页 → 上游那条写死的相对路径
    //      只有「课表 HTML 里没有 TaskActivity」才是硬错误（那是真的没课或没登录）
    //   ⑥ 桥调用（showToast / showAlert / notifyTaskCompletion / saveImportedCourses /
    //      savePresetTimeSlots）与作息表全部没有移植；作息在 parse.js 里进载荷并写进 warnings
    //   ⑦ 课表页的地址优先从**探测页里的表单 action** 里找（「courseTable.action」 /
    //      「courseTableStd.action」 / 「courseTableForStd!courseTable.action」），
    //      找不到才回落到上游那条写死的相对路径
    //
    // 取数方式（同源相对路径，最多 5 条，全部打在当前页面的那个主机上）：
    //   ① GET  /eams/courseTableForStd.action?sf_request_type=ajax   探测页（ids 与学期选择框）
    //   ② GET  /eams/courseTableStd.action?sf_request_type=ajax      备选探测页（部分部署用这条）
    //   ③ POST /eams/dataQuery.action?sf_request_type=ajax           dataType=semesterCalendar 学期列表
    //   ④ POST /eams/courseTableForStd!courseTable.action?sf_request_type=ajax   课表 HTML（内嵌 TaskActivity）
    //      （探测页里能读到表单 action 时用它那条；读不到才用上面这条上游同款路径）
    //
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。
    //
    // ⚠️ 内存：本文件（与 parse.js）的注释、正则、字符串里**一律不出现**字面 NUL 字节，
    //    也不出现 unicode 转义序列（某种编辑工具会把转义序列落成真的控制字符，让文件变二进制）。

    var EAMS = '/eams';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维 EAMS 的上下文路径是 /eams；挂在前缀下（门户 / 网关）时跟着当前地址里的那一段走
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
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
        });
    }

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    // 取不到就当没有（探测页与学期列表都是「有更好、没有也能继续」的接口）
    function optionalGet(url) {
        return request(url, 'GET', null).then(function (text) {
            return textOf(text);
        }, function () {
            return '';
        });
    }

    function optionalPost(url, body) {
        return request(url, 'POST', body).then(function (text) {
            return textOf(text);
        }, function () {
            return '';
        });
    }

    function groupOf(source, pattern, group) {
        var m = pattern.exec(textOf(source));
        return m ? textOf(m[group || 1]) : '';
    }

    function localTodayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    // 从页面 HTML 里读 ids（学号）与学期选择框的 tagId。**只在本次取数里用**，
    // 不进输出（输出里只留探测页 HTML 本身，由 parse.js 就地正则读）。
    var RE_IDS_FORM = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["']([0-9]+)["']\s*\)/;
    var RE_IDS_PARAM = /["']ids["']\s*[,:=]\s*["']?([0-9]+)/;
    var RE_TAG_ID = /id=["'](semesterBar[0-9]+Semester)["']/;

    function probeOf(html) {
        var source = textOf(html);
        var ids = groupOf(source, RE_IDS_FORM) || groupOf(source, RE_IDS_PARAM);
        var tagId = groupOf(source, RE_TAG_ID);
        var value = '';
        if (tagId) {
            var at = source.indexOf('id="' + tagId + '"');
            if (at < 0) at = source.indexOf("id='" + tagId + "'");
            if (at >= 0) {
                var tagStart = source.lastIndexOf('<', at);
                var tagEnd = source.indexOf('>', at);
                var tag = source.substring(tagStart < 0 ? 0 : tagStart, tagEnd < 0 ? source.length : tagEnd);
                value = groupOf(tag, /value=["']?([0-9]+)/);
            }
        }
        return { ids: ids, tagId: tagId, value: value };
    }

    // 探测页 / 课表页里的表单 action（本校的课表地址可能不是上游写死的那一条）
    function courseTableActionOf(html) {
        var source = textOf(html);
        var re = /action\s*=\s*["']([^"']*courseTable[^"']*)["']/gi;
        var m = re.exec(source);
        while (m) {
            var url = m[1];
            if (/courseTableStd\.action/i.test(url)) return url;
            if (/courseTable(?:ForStd)?!courseTable\.action/i.test(url)) return url;
            m = re.exec(source);
        }
        return '';
    }

    function absoluteOf(url) {
        if (/^https?:\/\//i.test(url)) return url;
        var path = url.charAt(0) === '/' ? url : '/' + url;
        return originOf() + path;
    }

    // ---------- semesterCalendar 响应的解析（不执行取回来的代码） ----------
    // 这个接口返回的是**裸 JS 对象字面量**（键可以不带引号），上游直接把它塞进 Function 构造器执行
    // —— 那是手册 §5 第 6 条禁止的（表达式来自网络）。这里改成「花括号配平切块 + 逐字段键值对」，
    // 只把字段读出来，一行取回来的代码都不执行。解析不出任何学期时交空数组，parse.js 会兜底并出声。
    var RE_FIELD = /["']?([A-Za-z_$][A-Za-z0-9_$]*)["']?\s*:\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^,}]*)/g;

    function unquoteJs(raw) {
        var s = textOf(raw).replace(/^\s+|\s+$/g, '');
        if (!s) return '';
        var first = s.charAt(0);
        if ((first === '"' || first === "'") && s.length >= 2 && s.charAt(s.length - 1) === first) {
            return s.substring(1, s.length - 1);
        }
        return s;
    }

    function matchBraceGroup(source, openIndex) {
        var depth = 0;
        var quote = '';
        var i;
        var ch;
        for (i = openIndex; i < source.length; i++) {
            ch = source.charAt(i);
            if (quote) {
                if (ch === '\\') { i++; continue; }
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; continue; }
            if (ch === '{') { depth++; continue; }
            if (ch === '}') {
                depth--;
                if (depth === 0) return i;
            }
        }
        return -1;
    }

    function fieldsOf(body) {
        var fields = {};
        RE_FIELD.lastIndex = 0;
        var m;
        while ((m = RE_FIELD.exec(body)) !== null) {
            var key = m[1];
            var value = unquoteJs(m[2]);
            if (value === '' || value === 'null' || value === 'undefined') continue;
            if (fields[key] === undefined) fields[key] = value;
        }
        return fields;
    }

    var DATE_START_KEYS = ['startDate', 'beginDate', 'dateBegin', 'startTime', 'dateStart', 'start'];
    var DATE_END_KEYS = ['endDate', 'finishDate', 'dateEnd', 'endTime', 'dateFinish', 'end'];

    function pickDate(fields, keys) {
        var i;
        for (i = 0; i < keys.length; i++) {
            var value = fields[keys[i]];
            if (value !== undefined && value !== null && value !== '') return textOf(value);
        }
        return '';
    }

    function parseSemesterResponse(raw) {
        var source = textOf(raw);
        var out = [];
        if (!source) return out;
        // 逐个花括号取「配平的那一块」：里面还嵌着花括号的块是容器（外层那层就是整个
        // {semesters:{...}} 的包装），真正的一个学期是**最里层**那块平铺对象。
        // 全都嵌着的（没有最里层）才退回容器块。
        var inner = [];
        var outer = [];
        var i;
        for (i = 0; i < source.length && inner.length + outer.length < 500; i++) {
            if (source.charAt(i) !== '{') continue;
            var close = matchBraceGroup(source, i);
            if (close < 0) continue;
            var body = source.substring(i + 1, close);
            if (body.indexOf('{') < 0) inner.push(body); else outer.push(body);
        }
        var groups = inner.length ? inner : outer;
        for (i = 0; i < groups.length; i++) {
            var fields = fieldsOf(groups[i]);
            if (fields.id === undefined || !textOf(fields.id)) continue;
            var entry = {
                id: textOf(fields.id),
                schoolYear: textOf(fields.schoolYear),
                term: textOf(fields.name),
                startDate: pickDate(fields, DATE_START_KEYS),
                endDate: pickDate(fields, DATE_END_KEYS)
            };
            var seen = false;
            for (var k = 0; k < out.length; k++) if (out[k].id === entry.id) seen = true;
            if (!seen) out.push(entry);
        }
        return out;
    }

    function currentSemesterIdOf(raw) {
        var source = textOf(raw);
        var positions = [/["']?(?:currentSemesterId|semesterId)["']?\s*:\s*["']?([0-9]+)/, /["']?value["']?\s*:\s*["']?([0-9]+)/];
        for (var i = 0; i < positions.length; i++) {
            var m = positions[i].exec(source);
            if (m) return textOf(m[1]);
        }
        return '';
    }

    var base = eamsBase();
    var entryHtml = '';

    // ① 探测页 → ② 备选探测页
    function fetchEntry() {
        return optionalGet(base + '/courseTableForStd.action?sf_request_type=ajax').then(function (html) {
            if (html) return html;
            return optionalGet(base + '/courseTableStd.action?sf_request_type=ajax');
        });
    }

    // ③ 学期列表：探测到 tagId 就用它（上游同款请求体），否则只带 dataType
    function fetchSemesters(tagId) {
        var body = tagId
            ? 'tagId=' + encodeURIComponent(tagId) + '&dataType=semesterCalendar'
            : 'dataType=semesterCalendar';
        return optionalPost(base + '/dataQuery.action?sf_request_type=ajax', body)
            .then(function (text) {
                if (text && text.indexOf('semesters') >= 0) return text;
                // 带 tagId 没拿到时再试一次不带（有的部署只认 dataType）
                if (!tagId) return '';
                return optionalPost(base + '/dataQuery.action?sf_request_type=ajax', 'dataType=semesterCalendar')
                    .then(function (retry) {
                        return (retry && retry.indexOf('semesters') >= 0) ? retry : '';
                    });
            });
    }

    return fetchEntry().then(function (html) {
        entryHtml = html;
        var probe = probeOf(html);
        return fetchSemesters(probe.tagId).then(function (semesterRaw) {
            if (!probe.ids) {
                throw new Error(
                    '没能从教务页面读到你的选课身份（探测接口没有返回 ids）：' +
                    '登录状态可能已失效，或教务系统改了课表页。请重新登录并打开课表页后再点「提取课表」'
                );
            }
            var body = [
                'ignoreHead=1',
                'setting.kind=std',
                'startWeek=',
                'semester.id=' + encodeURIComponent(probe.value),
                'ids=' + encodeURIComponent(probe.ids)
            ].join('&');

            var action = courseTableActionOf(html);
            var courseUrl = action
                ? absoluteOf(action)
                : base + '/courseTableForStd!courseTable.action?sf_request_type=ajax';
            if (courseUrl.indexOf('?') < 0) courseUrl += '?sf_request_type=ajax';

            return request(courseUrl, 'POST', body).then(function (courseHtml) {
                var text = textOf(courseHtml);
                if (!text || text.indexOf('TaskActivity') < 0) {
                    throw new Error(
                        '教务系统没有返回可解析的课表数据：登录状态可能已失效，或这个学期还没排课。' +
                        '请重新登录、在课表页里确认能看到课，再点「提取课表」'
                    );
                }
                // 只交出解析后的学期字段（不交原始响应）：原始响应里可能夹带当前学期以外的
                // 学期对象与其它字段，交出去等于把它们带进 fixture 与日志。学号同样不进输出。
                return JSON.stringify({
                    source: action ? 'form-action' : 'relative',
                    today: localTodayIso(),
                    entryHtml: entryHtml,
                    semesters: parseSemesterResponse(semesterRaw),
                    currentSemesterId: currentSemesterIdOf(semesterRaw) || probe.value || null,
                    courseHtml: text
                });
            });
        });
    });
})()
