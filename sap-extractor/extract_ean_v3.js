(async function() {
  var BASE = 'https://de.fsm.cloud.sap/master-data-management-v2/portal/items/';
  var BATCH = 15;
  var DELAY = 50;
  var PAGE_SIZE = 1000;

  console.log('=== SAP - Extractor de EAN Codes v3 ===');
  console.log('API: ' + BASE);
  console.log('');

  // PASO 1: Descargar listado
  console.log('[PASO 1/3] Descargando listado de piezas...');
  var allItems = [];
  var page = 1;
  var keepGoing = true;
  var listUrl = '';

  while (keepGoing) {
    var urls = [
      BASE + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=',
      BASE + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=&objectType=ITEM',
      BASE + 'search?onlyActive=true&pageSize=' + PAGE_SIZE + '&page=' + page + '&searchQuery='
    ];

    var success = false;
    for (var u = 0; u < urls.length; u++) {
      try {
        var resp = await fetch(urls[u], {credentials: 'include'});
        if (!resp.ok) continue;

        var json = await resp.json();
        var items;

        if (Array.isArray(json)) {
          items = json;
        } else if (json.data && Array.isArray(json.data)) {
          items = json.data;
        } else {
          var keys = Object.keys(json);
          items = null;
          for (var k = 0; k < keys.length; k++) {
            if (Array.isArray(json[keys[k]]) && json[keys[k]].length > 0) {
              items = json[keys[k]];
              break;
            }
          }
        }

        if (items && items.length > 0) {
          allItems = allItems.concat(items);
          listUrl = urls[u];
          console.log('  Pagina ' + page + ': ' + items.length + ' items (total: ' + allItems.length + ')');
          keepGoing = items.length >= PAGE_SIZE;
          page++;
          success = true;
          break;
        }
      } catch(e) {
        console.log('  URL ' + (u+1) + ' fallo: ' + e.message);
      }
    }

    if (!success) {
      if (allItems.length === 0) {
        console.error('No se pudo descargar el listado.');
        console.log('URLs intentadas:');
        urls.forEach(function(url) { console.log('  ' + url); });
        alert('Error: No se pudo descargar el listado de items. Asegurate de estar logueado en SAP.');
        return;
      }
      keepGoing = false;
    }
  }

  console.log('Total items en listado: ' + allItems.length);
  console.log('URL que funciono: ' + listUrl);

  // Verificar si ya incluye EANs
  var hasEans = allItems[0] && allItems[0].eans !== undefined;
  var finalData;

  if (hasEans) {
    console.log('');
    console.log('[PASO 2/3] El listado ya incluye EANs! No se necesitan mas peticiones.');
    finalData = allItems;
  } else {
    console.log('');
    console.log('[PASO 2/3] Descargando EAN de ' + allItems.length + ' piezas una por una...');
    console.log('Tiempo estimado: 2-4 minutos. No cierres la pestana.');
    console.log('');

    finalData = [];
    var done = 0;
    var errors = 0;
    var startTime = Date.now();

    for (var i = 0; i < allItems.length; i += BATCH) {
      var batch = allItems.slice(i, i + BATCH);
      var promises = batch.map(function(item) {
        return fetch(BASE + 'readOne?id=' + item.id, {credentials: 'include'})
          .then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
          })
          .then(function(j) {
            done++;
            return j.data || j;
          })
          .catch(function(e) {
            done++;
            errors++;
            return {code: item.code || item.itemCode || '', name: item.name || item.itemName || '', eans: []};
          });
      });

      var results = await Promise.all(promises);
      finalData = finalData.concat(results);

      if (done % 100 === 0 || done === allItems.length) {
        var pct = Math.round(done / allItems.length * 100);
        var elapsed = Math.round((Date.now() - startTime) / 1000);
        var remaining = done > 0 ? Math.round(elapsed / done * (allItems.length - done)) : 0;
        console.log('  ' + done + '/' + allItems.length + ' (' + pct + '%) - ' + elapsed + 's transcurridos, ~' + remaining + 's restantes');
      }

      if (i + BATCH < allItems.length) {
        await new Promise(function(resolve) { setTimeout(resolve, DELAY); });
      }
    }

    var totalTime = Math.round((Date.now() - startTime) / 1000);
    console.log('  Completado en ' + totalTime + ' segundos.');
    if (errors > 0) console.log('  ' + errors + ' items con error de descarga.');
  }

  // PASO 3: Generar CSV
  console.log('');
  console.log('[PASO 3/3] Generando archivo CSV...');

  var csv = '﻿' + 'Part No;Description;EAN Code;Group\n';
  var conEan = 0;
  var sinEan = 0;

  for (var i = 0; i < finalData.length; i++) {
    var item = finalData[i];
    var code = String(item.code || item.itemCode || '').replace(/"/g, '""');
    var nm = String(item.name || item.itemName || '').replace(/"/g, '""');
    var group = String(item.groupName || item.group || '').replace(/"/g, '""');
    var eans = item.eans || item.barCodes || [];
    var eanStr = Array.isArray(eans) ? eans.join(', ') : String(eans || '');

    if (eanStr.length > 0) conEan++; else sinEan++;
    csv += '"' + code + '";"' + nm + '";"' + eanStr + '";"' + group + '"\n';
  }

  var blob = new Blob([csv], {type: 'text/csv;charset=utf-8;'});
  var a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'SAP_Piezas_EAN.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);

  console.log('');
  console.log('========================================');
  console.log('  EXTRACCION COMPLETADA');
  console.log('========================================');
  console.log('  Total piezas: ' + finalData.length);
  console.log('  Con codigo EAN: ' + conEan);
  console.log('  Sin codigo EAN: ' + sinEan);
  console.log('  Archivo: SAP_Piezas_EAN.csv');

  alert('Completado!\n\nTotal piezas: ' + finalData.length + '\nCon codigo EAN: ' + conEan + '\nSin codigo EAN: ' + sinEan + '\n\nEl archivo CSV se ha descargado automaticamente.');
})();
