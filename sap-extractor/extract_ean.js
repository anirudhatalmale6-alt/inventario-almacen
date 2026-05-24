(async function() {
  var BATCH = 15;
  var DELAY = 50;
  var PAGE_SIZE = 1000;

  console.log('=== SAP B1 - Extractor de EAN Codes ===');
  console.log('Buscando URL de la API...');

  var resources = performance.getEntriesByType('resource');
  var readOneBase = '';
  var readAllBase = '';
  var readAllExtra = '';

  for (var i = 0; i < resources.length; i++) {
    var name = resources[i].name;
    if (name.indexOf('readOne?id=') > -1 && !readOneBase) {
      readOneBase = name.substring(0, name.indexOf('readOne?')) + 'readOne';
    }
    if (name.indexOf('readAll?') > -1 && !readAllBase) {
      readAllBase = name.substring(0, name.indexOf('readAll?')) + 'readAll';
      var afterQ = name.substring(name.indexOf('readAll?') + 8);
      var params = afterQ.split('&');
      var extras = [];
      for (var p = 0; p < params.length; p++) {
        var key = params[p].split('=')[0];
        if (key !== 'page' && key !== 'pageSize') {
          extras.push(params[p]);
        }
      }
      readAllExtra = extras.length > 0 ? '&' + extras.join('&') : '';
    }
  }

  if (!readOneBase && !readAllBase) {
    alert('ERROR: No se encontro la API. Haz clic en una pieza del listado primero y ejecuta el script de nuevo.');
    return;
  }

  if (!readAllBase && readOneBase) {
    readAllBase = readOneBase.replace('/readOne', '/readAll').replace('readOne', 'readAll');
  }
  if (!readOneBase && readAllBase) {
    readOneBase = readAllBase.replace('/readAll', '/readOne').replace('readAll', 'readOne');
  }

  console.log('readAll: ' + readAllBase);
  console.log('readOne: ' + readOneBase);

  // PASO 1: Descargar listado de items
  console.log('\n[PASO 1] Descargando listado de piezas...');
  var allItems = [];
  var page = 1;
  var keepGoing = true;

  while (keepGoing) {
    try {
      var url = readAllBase + '?page=' + page + '&pageSize=' + PAGE_SIZE + readAllExtra;
      var resp = await fetch(url, {credentials: 'include'});

      if (!resp.ok) {
        console.log('Error HTTP ' + resp.status + '. Intentando sin parametros extra...');
        url = readAllBase + '?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=';
        resp = await fetch(url, {credentials: 'include'});
      }

      var json = await resp.json();
      var items;

      if (Array.isArray(json)) {
        items = json;
      } else if (json.data && Array.isArray(json.data)) {
        items = json.data;
      } else {
        var keys = Object.keys(json);
        items = [];
        for (var k = 0; k < keys.length; k++) {
          if (Array.isArray(json[keys[k]]) && json[keys[k]].length > 0) {
            items = json[keys[k]];
            break;
          }
        }
      }

      if (items.length === 0) {
        keepGoing = false;
      } else {
        allItems = allItems.concat(items);
        console.log('  Pagina ' + page + ': ' + items.length + ' items (total: ' + allItems.length + ')');
        keepGoing = items.length >= PAGE_SIZE;
        page++;
      }
    } catch(e) {
      console.error('Error en pagina ' + page + ':', e.message);
      keepGoing = false;
    }
  }

  if (allItems.length === 0) {
    alert('ERROR: No se pudieron descargar los items. Asegurate de estar logueado en SAP.');
    return;
  }

  console.log('Total items en listado: ' + allItems.length);

  // Verificar si readAll ya incluye EANs
  var hasEans = allItems[0].eans !== undefined;

  var finalData;
  if (hasEans) {
    console.log('\n[PASO 2] readAll ya incluye codigos EAN. No se necesitan peticiones adicionales.');
    finalData = allItems;
  } else {
    console.log('\n[PASO 2] Descargando detalles de ' + allItems.length + ' piezas para obtener EAN codes...');
    console.log('Esto puede tardar 2-3 minutos. No cierres esta pestana.');

    finalData = [];
    var done = 0;
    var errors = 0;

    for (var i = 0; i < allItems.length; i += BATCH) {
      var batch = allItems.slice(i, i + BATCH);
      var promises = batch.map(function(item) {
        return fetch(readOneBase + '?id=' + item.id, {credentials: 'include'})
          .then(function(r) { return r.json(); })
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

      if (done % 150 === 0 || done === allItems.length) {
        console.log('  Progreso: ' + done + '/' + allItems.length + ' (' + Math.round(done / allItems.length * 100) + '%)');
      }

      if (i + BATCH < allItems.length) {
        await new Promise(function(resolve) { setTimeout(resolve, DELAY); });
      }
    }

    if (errors > 0) {
      console.log('  Advertencia: ' + errors + ' items tuvieron error al descargar.');
    }
  }

  // PASO 3: Generar CSV
  console.log('\n[PASO 3] Generando archivo CSV...');
  var csv = '﻿';
  csv += 'Part No;Description;EAN Code;Group\n';

  var conEan = 0;
  var sinEan = 0;

  for (var i = 0; i < finalData.length; i++) {
    var item = finalData[i];
    var code = String(item.code || item.itemCode || '').replace(/"/g, '""');
    var name = String(item.name || item.itemName || '').replace(/"/g, '""');
    var group = String(item.groupName || item.group || '').replace(/"/g, '""');
    var eans = item.eans || item.barCodes || [];
    var eanStr = '';
    if (Array.isArray(eans)) {
      eanStr = eans.join(', ');
    } else {
      eanStr = String(eans || '');
    }

    if (eanStr.length > 0) { conEan++; } else { sinEan++; }
    csv += '"' + code + '";"' + name + '";"' + eanStr + '";"' + group + '"\n';
  }

  var blob = new Blob([csv], {type: 'text/csv;charset=utf-8;'});
  var a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'SAP_Piezas_EAN.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);

  console.log('\n========================================');
  console.log('  EXTRACCION COMPLETADA');
  console.log('========================================');
  console.log('  Total piezas: ' + finalData.length);
  console.log('  Con codigo EAN: ' + conEan);
  console.log('  Sin codigo EAN: ' + sinEan);
  console.log('  Archivo: SAP_Piezas_EAN.csv');

  alert('Completado!\n\nTotal piezas: ' + finalData.length + '\nCon codigo EAN: ' + conEan + '\nSin codigo EAN: ' + sinEan + '\n\nEl archivo CSV se ha descargado automaticamente.');
})();
