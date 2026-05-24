(async function() {
  var BATCH = 15;
  var DELAY = 50;
  var PAGE_SIZE = 1000;

  console.log('=== SAP - Extractor de EAN Codes ===');

  // STEP 1: Find API base URL using multiple methods
  var base = '';

  // Method 1: Check performance entries (newest first)
  var entries = performance.getEntriesByType('resource');
  for (var i = entries.length - 1; i >= 0; i--) {
    var n = entries[i].name;
    if (n.indexOf('readOne?') > -1) {
      base = n.substring(0, n.indexOf('readOne?'));
      console.log('URL encontrada en performance entries');
      break;
    }
    if (n.indexOf('readAll?') > -1) {
      base = n.substring(0, n.indexOf('readAll?'));
      console.log('URL encontrada en performance entries');
      break;
    }
  }

  // Method 2: Intercept fetch/XHR - wait for user to click an item
  if (!base) {
    console.log('');
    console.log('>>> HAZ CLIC EN UNA PIEZA DEL LISTADO DE LA IZQUIERDA <<<');
    console.log('(El script detectara la URL automaticamente)');
    console.log('');

    base = await new Promise(function(resolve) {
      var done = false;

      var timer = setTimeout(function() {
        if (!done) {
          done = true;
          resolve('');
        }
      }, 60000);

      // Intercept fetch
      var origFetch = window.fetch;
      window.fetch = function() {
        var url = '';
        if (typeof arguments[0] === 'string') {
          url = arguments[0];
        } else if (arguments[0] && arguments[0].url) {
          url = arguments[0].url;
        }
        var result = origFetch.apply(this, arguments);
        if (!done && (url.indexOf('readOne?') > -1 || url.indexOf('readAll?') > -1)) {
          done = true;
          window.fetch = origFetch;
          clearTimeout(timer);
          if (url.indexOf('http') !== 0) {
            url = new URL(url, window.location.href).href;
          }
          var endpoint = url.indexOf('readOne?') > -1 ? 'readOne?' : 'readAll?';
          resolve(url.substring(0, url.indexOf(endpoint)));
        }
        return result;
      };

      // Intercept XMLHttpRequest
      var origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function() {
        var url = arguments[1] || '';
        if (!done && (url.indexOf('readOne?') > -1 || url.indexOf('readAll?') > -1)) {
          done = true;
          XMLHttpRequest.prototype.open = origOpen;
          clearTimeout(timer);
          if (url.indexOf('http') !== 0) {
            url = new URL(url, window.location.href).href;
          }
          var endpoint = url.indexOf('readOne?') > -1 ? 'readOne?' : 'readAll?';
          resolve(url.substring(0, url.indexOf(endpoint)));
        }
        return origOpen.apply(this, arguments);
      };
    });
  }

  // Method 3: Manual input
  if (!base) {
    console.log('No se detecto automaticamente.');
    console.log('En la pestana Network, haz clic derecho sobre "readOne?id=..." > Copy > Copy URL');
    var manualUrl = prompt('Pega aqui la URL completa del readOne:');
    if (manualUrl && manualUrl.indexOf('readOne') > -1) {
      base = manualUrl.substring(0, manualUrl.indexOf('readOne?'));
    }
  }

  if (!base) {
    alert('No se pudo encontrar la URL de la API.');
    return;
  }

  console.log('API encontrada: ' + base);
  console.log('');

  // STEP 2: Get all items via readAll
  console.log('[PASO 1/3] Descargando listado de piezas...');
  var allItems = [];
  var page = 1;
  var keepGoing = true;

  while (keepGoing) {
    var urls = [
      base + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=',
      base + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=&objectType=ITEM',
      base + 'search?onlyActive=true&pageSize=' + PAGE_SIZE + '&page=' + page + '&searchQuery='
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
            if (Array.isArray(json[keys[k]]) && json[keys[k]].length > 0 && json[keys[k]][0].id) {
              items = json[keys[k]];
              break;
            }
          }
        }

        if (items && items.length > 0) {
          allItems = allItems.concat(items);
          console.log('  Pagina ' + page + ': ' + items.length + ' items (total: ' + allItems.length + ')');
          keepGoing = items.length >= PAGE_SIZE;
          page++;
          success = true;
          break;
        }
      } catch(e) {
        // Try next URL format
      }
    }

    if (!success) {
      if (allItems.length === 0) {
        console.error('Error: No se pudo descargar el listado.');
        console.log('URLs intentadas:');
        urls.forEach(function(u) { console.log('  ' + u); });
      }
      keepGoing = false;
    }
  }

  if (allItems.length === 0) {
    alert('Error: No se encontraron items. Revisa la consola para mas detalles.');
    return;
  }

  console.log('Total items en listado: ' + allItems.length);

  // Check if list already includes EANs
  var hasEans = allItems[0].eans !== undefined;

  var finalData;
  if (hasEans) {
    console.log('');
    console.log('[PASO 2/3] El listado ya incluye codigos EAN!');
    finalData = allItems;
  } else {
    console.log('');
    console.log('[PASO 2/3] Descargando detalles de ' + allItems.length + ' piezas (EAN codes)...');
    console.log('Esto puede tardar 2-4 minutos. No cierres esta pestana.');

    finalData = [];
    var done = 0;
    var errors = 0;

    for (var i = 0; i < allItems.length; i += BATCH) {
      var batch = allItems.slice(i, i + BATCH);
      var promises = batch.map(function(item) {
        return fetch(base + 'readOne?id=' + item.id, {credentials: 'include'})
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
        console.log('  Progreso: ' + done + '/' + allItems.length + ' (' + pct + '%)');
      }

      if (i + BATCH < allItems.length) {
        await new Promise(function(resolve) { setTimeout(resolve, DELAY); });
      }
    }

    if (errors > 0) {
      console.log('  Nota: ' + errors + ' items no se pudieron descargar.');
    }
  }

  // STEP 3: Generate CSV
  console.log('');
  console.log('[PASO 3/3] Generando archivo CSV...');

  var bom = '﻿';
  var csv = bom + 'Part No;Description;EAN Code;Group\n';
  var conEan = 0;
  var sinEan = 0;

  for (var i = 0; i < finalData.length; i++) {
    var item = finalData[i];
    var code = String(item.code || item.itemCode || '').replace(/"/g, '""');
    var nm = String(item.name || item.itemName || '').replace(/"/g, '""');
    var group = String(item.groupName || item.group || '').replace(/"/g, '""');
    var eans = item.eans || item.barCodes || [];
    var eanStr = Array.isArray(eans) ? eans.join(', ') : String(eans || '');

    if (eanStr.length > 0) { conEan++; } else { sinEan++; }
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
