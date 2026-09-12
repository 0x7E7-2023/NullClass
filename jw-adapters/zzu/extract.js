(function () {
    // 郑州大学教务适配器（树维 for-std 平台，jwxt.zzu.edu.cn）
    // 移植自 shiguang_warehouse 的 ZZU/zzu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 LilyCarry）
    //   上游快照：e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //   学校名与登录地址取自同目录 adapters.yaml（maintainer LilyCarry）：
    //   CAS 登录 https://cas.s.zzu.edu.cn/cas/a/login?service=https%3A%2F%2Fjwxt.zzu.edu.cn%2Fstudent%2Fsso%2Flogin
    //
    // 平台说明：本脚本与同批移植的 jstc 走**同一套接口**（上游 ZZU / JSTC / CUP / CUPK 四份
    // 脚本在「取数路径 + 行字段名」两层逐字同构）：#allSemesters 学期列表
    // + /student/ws/semester/get/{id} 学期元数据 + .../semester/{id}/print-data 的
    // studentTableVms[0].activities。我们既有的 ustc 是同一平台的**另一套取数接口**
    // （get-data + POST datum 的 lessonList/scheduleList），与本脚本**不同构**。
    // 同构到哪一层、哪几层能复用、哪几层不能，见同目录 AUDIT.md §3。
    //
    // 移植改动：
    //   ① 去掉「选学期」弹窗：改取用户此刻在教务页面上打开的学期（URL 里的 /semester/{id}），
    //      其次取 #allSemesters 里**写了 selected 属性**的那一项，最后才取列表第一个。
    //      上游 zzu.js 写的是 hasAttribute("selected") 加 opt.selected 的或 —— 浏览器会把一个
    //      selected 属性都没标的页面里的**第一个** option 的 .selected 置为 true，于是那条
    //      判据几乎恒真，「没标出当前学期」永远发不出来。这里只看属性，见 AUDIT.md §5.2。
    //   ② 降到 ES5（Promise 链，无 async/await），去掉拾光桥（showToast / notifyTaskCompletion）。
    //   ③ 主机名不写死：当前页面已经站在教务应用里（路径含 /student/）且域名在 zzu.edu.cn 下时，
    //      用当前页面的源（上游 zzu.js 用的就是相对路径，这样换一个本校教务入口也能用）；
    //      否则退回写死的教务主机。两种情况下请求目标都是本校域名，不会外发。
    //   ④ 除上游读的 activities 外，多原样转交两样东西（上游把这两个值直接写死成常量）：
    //      · /student/ws/semester/get/{id} 的原始 JSON —— 里面有 startDate / endDate
    //      · print-data 里 timeTableLayout.courseUnitList —— 学校真实的作息表
    //      （上游 zzu.js 只读 activities，作息表用脚本内置的 12 节；同平台的 CUP/CUPK 读的
    //      就是 courseUnitList，字段名 indexNo / startTime / endTime 取自它们。）
    //   ⑤ 不做任何计算：周次切段、课程聚合、开学日回退、总周数、作息表取舍全在 parse.js
    //      （CI 只能跑 parse.js，见 docs/jw-adapter-testing.md §1）。
    //
    // 请求目标（3 个 GET，全部是本校教务域，见同目录 AUDIT.md §1）：
    //   ① GET /student/for-std/course-table                    HTML，取 #allSemesters
    //   ② GET /student/ws/semester/get/{semesterId}            JSON，学期起止日期
    //          （拿不到会降级：交 null，由 parse.js 推算开学日并写进 warnings，不阻断导入）
    //   ③ GET /student/for-std/course-table/semester/{id}/print-data
    //          ?semesterId={id}&hasExperiment=true             JSON，排课记录与作息表
    //
    // 只读课表：不读成绩/学籍/个人信息，不写页面，不外发。

    var FALLBACK_ORIGIN = 'https://jwxt.zzu.edu.cn';

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function snippetOf(body) {
        var snippet = String(body === null || body === undefined ? '' : body).replace(/\s+/g, ' ');
        if (snippet.length > 160) snippet = snippet.slice(0, 160) + '…';
        return snippet;
    }

    // 教务应用的来源。页面不在本校域名下时不用它的源，防止被别处的页面借去请求。
    function origin() {
        if (typeof window !== 'undefined' && window.location) {
            var href = String(window.location.href || '');
            var m = /^(https?:\/\/[^\/]+)/i.exec(href);
            if (m && href.indexOf('/student/') !== -1) {
                var host = m[1].replace(/^https?:\/\//i, '').replace(/:\d+$/, '').toLowerCase();
                if (host === 'zzu.edu.cn' || /\.zzu\.edu\.cn$/.test(host)) return m[1];
            }
        }
        return FALLBACK_ORIGIN;
    }

    function request(url, accept) {
        return fetch(url, {
            method: 'GET',
            credentials: 'include',
            headers: {
                'Accept': accept,
                'X-Requested-With': 'XMLHttpRequest'
            }
        }).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：请先在页面里登录，并打开「课表查询」页面');
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回异常状态码：' + response.status + '（' + url + '）');
            }
            return response;
        }, function () {
            throw new Error('连不上教务系统：请确认已经登录、并且当前打开的是教务系统的页面，再点「提取课表」');
        });
    }

    function getText(url) {
        return request(url, 'text/html,application/xhtml+xml,*/*;q=0.8').then(function (response) {
            return response.text();
        });
    }

    function getJson(url) {
        return request(url, 'application/json, */*;q=0.1').then(function (response) {
            return response.text().then(function (body) {
                try {
                    return JSON.parse(body);
                } catch (e) {
                    throw new Error('教务接口没有返回合法 JSON（' + url + '）：' + snippetOf(body));
                }
            });
        });
    }

    // 学期元数据是「有更好、没有也能跑」的那一半：拿不到就交 null，
    // 由 parse.js 推算开学日期并在 warnings 里说明（推算了什么必须让用户看见）。
    function getJsonOptional(url) {
        return getJson(url).then(null, function () {
            return null;
        });
    }

    // #allSemesters 的选项：结构化解析优先（属性顺序/引号都不挑），失败再退回正则。
    function optionsFromDocument(html) {
        var out = [];
        if (typeof DOMParser === 'undefined') return out;
        var doc = null;
        try {
            doc = new DOMParser().parseFromString(html, 'text/html');
        } catch (e) {
            return out;
        }
        if (!doc) return out;
        var select = doc.getElementById('allSemesters');
        if (!select) return out;
        var options = select.getElementsByTagName('option');
        for (var i = 0; i < options.length; i++) {
            var id = text(options[i].value);
            var label = text(options[i].textContent);
            if (!id || !label) continue;
            // 「页面标没标出当前学期」必须看 selected **属性**，不能看 .selected ——
            // 浏览器会把「一个都没标 selected」时的第一个 option 的 .selected 置为 true，
            // 那样每个页面都会被判成「标出了当前学期」，parse.js 里那条提醒永远发不出来
            // （也让这里与下面的正则兜底（看属性）口径一致）。
            out.push({ id: id, label: label, selected: options[i].hasAttribute('selected') === true });
        }
        return out;
    }

    function optionsFromRegex(fragment) {
        var out = [];
        var regex = /<option([^>]*)>([\s\S]*?)<\/option>/gi;
        var match;
        while ((match = regex.exec(fragment)) !== null) {
            var attrs = match[1];
            var valueMatch = /value\s*=\s*["']([^"']+)["']/.exec(attrs);
            if (!valueMatch) continue;
            var id = text(valueMatch[1]);
            var label = text(match[2].replace(/<[^>]+>/g, ''));
            if (!id || !label) continue;
            out.push({
                id: id,
                label: label,
                selected: /(\s|^)selected(\s|=|$)/i.test(attrs)
            });
        }
        return out;
    }

    function semestersFromHtml(html) {
        var out = optionsFromDocument(html);
        if (out.length) return out;
        // 正则兜底只在这一个 <select> 的片段里找，别把页面里别的下拉框当成学期
        var block = /<select[^>]*id\s*=\s*["']allSemesters["'][^>]*>([\s\S]*?)<\/select>/i.exec(html);
        if (!block) return out;
        return optionsFromRegex(block[1]);
    }

    // 用户此刻打开的学期（课表页 URL 形如 .../semester/461/print...）
    function semesterIdFromLocation() {
        if (typeof window === 'undefined' || !window.location) return null;
        var path = String(window.location.pathname || '');
        var search = String(window.location.search || '');
        var m = /\/semester\/([0-9A-Za-z_-]+)/.exec(path);
        if (m) return m[1];
        var q = /[?&]semesterId=([^&#]+)/.exec(search);
        return q ? decodeURIComponent(q[1]) : null;
    }

    function findById(list, id) {
        for (var i = 0; i < list.length; i++) {
            if (String(list[i].id) === String(id)) return list[i];
        }
        return null;
    }

    // 选学期：URL 里的 > 页面上标了 selected 的 > 列表第一个。
    // 选完必须把「凭什么选的」带出去 —— 猜的那两种情况要写进 warnings。
    function pickSemester(semesters) {
        var idFromUrl = semesterIdFromLocation();
        if (idFromUrl) {
            var found = findById(semesters, idFromUrl);
            return {
                id: idFromUrl,
                label: found ? found.label : null,
                pickedBy: 'url'
            };
        }
        for (var i = 0; i < semesters.length; i++) {
            if (semesters[i].selected) {
                return { id: semesters[i].id, label: semesters[i].label, pickedBy: 'selected' };
            }
        }
        if (semesters.length) {
            return { id: semesters[0].id, label: semesters[0].label, pickedBy: 'first' };
        }
        return null;
    }

    // 上游 zzu.js 对 activities 有三级兜底：studentTableVms[0] → studentTableVm → 顶层。
    // 这里照搬这三条路（它比 jstc 多两条），并把命中的那个对象也交出去取作息表。
    function activitiesFrom(published) {
        var tableVm = null;
        if (published.studentTableVms && published.studentTableVms.length) {
            tableVm = published.studentTableVms[0];
        } else if (published.studentTableVm) {
            tableVm = published.studentTableVm;
        }
        var activities = [];
        if (tableVm && tableVm.activities) activities = tableVm.activities;
        if (!activityArray(activities).length && published.activities) activities = published.activities;
        return { tableVm: tableVm, activities: activityArray(activities) };
    }

    function activityArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]' ? value : [];
    }

    function unitsFrom(tableVm, published) {
        if (tableVm && tableVm.timeTableLayout && tableVm.timeTableLayout.courseUnitList) {
            return activityArray(tableVm.timeTableLayout.courseUnitList);
        }
        if (published.timeTableLayout && published.timeTableLayout.courseUnitList) {
            return activityArray(published.timeTableLayout.courseUnitList);
        }
        return [];
    }

    // print-data 有没有「记录总数」字段我们没有真机样本。把几个常见名字里能读到的数字原样带出去，
    // 由 parse.js 跟 activities 的条数比对 —— 接口没回总数时不能只当第一页用（测试方案 §3.1）。
    // 读不到就什么都不带，parse.js 也不说话。
    var TOTAL_KEYS = ['total', 'totalCount', 'totalElements', 'totalSize', 'recordCount', 'count'];

    function totalSniff(source) {
        var out = null;
        if (!source || typeof source !== 'object') return out;
        for (var i = 0; i < TOTAL_KEYS.length; i++) {
            var value = source[TOTAL_KEYS[i]];
            if (typeof value === 'number' && isFinite(value) && value >= 0) {
                if (out === null) out = {};
                out[TOTAL_KEYS[i]] = value;
            }
        }
        return out;
    }

    var base = origin();

    return getText(base + '/student/for-std/course-table').then(function (html) {
        var semesters = semestersFromHtml(html);
        var picked = pickSemester(semesters);
        if (!picked) {
            throw new Error('没能在教务页面读到学期列表（#allSemesters）：请先打开「课表查询」页面，再点「提取课表」');
        }

        var id = encodeURIComponent(picked.id);
        var printUrl = base + '/student/for-std/course-table/semester/' + id +
            '/print-data?semesterId=' + id + '&hasExperiment=true';

        return Promise.all([
            getJsonOptional(base + '/student/ws/semester/get/' + id),
            getJson(printUrl)
        ]).then(function (results) {
            var published = results[1] || {};
            var found = activitiesFrom(published);

            // 原样交出去：activities 与 courseUnitList 都是教务返回的对象数组，一个字段都不改
            return JSON.stringify({
                term: {
                    id: picked.id,
                    label: picked.label,
                    pickedBy: picked.pickedBy
                },
                semesters: semesters,
                semesterMeta: results[0],
                activities: found.activities,
                courseUnitList: unitsFrom(found.tableVm, published),
                pageInfo: {
                    root: totalSniff(published),
                    tableVm: totalSniff(found.tableVm)
                }
            });
        });
    });
})()
