(function () {
    var rows = [];
    var tables = document.querySelectorAll('table');
    for (var i = 0; i < tables.length; i++) {
        var trs = tables[i].querySelectorAll('tr');
        for (var j = 0; j < trs.length; j++) {
            var cells = [];
            var tds = trs[j].querySelectorAll('td,th');
            for (var k = 0; k < tds.length; k++) {
                cells.push(tds[k].innerText.replace(/\s+/g, ' ').trim());
            }
            if (cells.length > 0) rows.push(cells);
        }
    }
    return JSON.stringify({ url: location.href, title: document.title, rows: rows });
})()
