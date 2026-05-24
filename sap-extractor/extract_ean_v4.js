(async function() {
  var BATCH = 15;
  var DELAY = 100;
  var PAGE_SIZE = 1000;
  var BASE = 'https://de.fsm.cloud.sap/master-data-management-v2/portal/items/';

  console.log('=== SAP - Extractor de EAN Codes v4 ===');
  console.log('');
  console.log('>>> HAZ CLIC EN UNA PIEZA DEL LISTADO <<<');
  console.log('(Necesito capturar las credenciales de tu sesion)');
  console.log('');

  // STEP 1: Capture auth headers by intercepting XHR and fetch
  var capturedHeaders = await new Promise(function(resolve) {
    var done = false;

    var timer = setTimeout(function() {
      if (!done) { done = true; resolve(null); }
    }, 60000);

    // Intercept fetch
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
      var result = origFetch.apply(this, arguments);
      if (!done && init && init.headers) {
        var h = init.headers;
        var authVal = '';
        if (h instanceof Headers) {
          authVal = h.get('authorization') || '';
        } else if (typeof h === 'object') {
          authVal = h.authorization || h.Authorization || '';
        }
        if (authVal.toLowerCase().indexOf('bearer') === 0) {
          done = true;
          window.fetch = origFetch;
          clearTimeout(timer);
          var out = {};
          if (h instanceof Headers) {
            h.forEach(function(v, k) { out[k] = v; });
          } else {
            Object.keys(h).forEach(function(k) { out[k] = h[k]; });
          }
          resolve(out);
        }
      }
      return result;
    };

    // Intercept XMLHttpRequest
    var origOpen = XMLHttpRequest.prototype.open;
    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    var pendingHeaders = {};

    XMLHttpRequest.prototype.open = function() {
      this._xhrUrl = arguments[1];
      pendingHeaders = {};
      return origOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
      pendingHeaders[name.toLowerCase()] = value;
      if (!done && name.toLowerCase() === 'authorization' && value.toLowerCase().indexOf('bearer') === 0) {
        done = true;
        XMLHttpRequest.prototype.open = origOpen;
        XMLHttpRequest.prototype.setRequestHeader = origSetHeader;
        clearTimeout(timer);
        var copy = {};
        Object.keys(pendingHeaders).forEach(function(k) { copy[k] = pendingHeaders[k]; });
        // Need to wait a tick to capture remaining headers
        setTimeout(function() {
          Object.keys(pendingHeaders).forEach(function(k) { copy[k] = pendingHeaders[k]; });
          resolve(copy);
        }, 100);
      }
      return origSetHeader.apply(this, arguments);
    };
  });

  if (!capturedHeaders) {
    alert('No se capturaron las credenciales. Ejecuta el script de nuevo y haz clic en una pieza.');
    return;
  }

  // Build headers for our requests
  var myHeaders = {
    'accept': 'application/json, text/plain, */*'
  };

  Object.keys(capturedHeaders).forEach(function(key) {
    var k = key.toLowerCase();
    if (k === 'authorization' || k.indexOf('x-cloud') === 0 || k.indexOf('x-client') === 0) {
      myHeaders[k] = capturedHeaders[key];
    }
  });

  if (!myHeaders['authorization']) {
    alert('No se encontro el token de autorizacion.');
    return;
  }

  console.log('Credenciales capturadas!');
  console.log('');

  // STEP 2: Get all items
  console.log('[PASO 1/3] Descargando listado de piezas...');
  var allItems = [];
  var page = 1;
  var keepGoing = true;
  var workingUrl = '';

  while (keepGoing) {
    var urls;
    if (workingUrl) {
      urls = [workingUrl.replace(/page=\d+/, 'page=' + page)];
    } else {
      urls = [
        BASE + 'search?onlyActive=true&pageSize=' + PAGE_SIZE + '&page=' + page + '&searchQuery=',
        BASE + 'search?pageSize=' + PAGE_SIZE + '&page=' + page + '&searchQuery=',
        BASE + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=',
        BASE + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE + '&searchQuery=&objectType=ITEM'
      ];
    }

    var success = false;
    for (var u = 0; u < urls.length; u++) {
      try {
        var resp = await fetch(urls[u], {
          method: 'GET',
          headers: myHeaders,
          credentials: 'include'
        });

        if (!resp.ok) {
          console.log('  Intento ' + (u + 1) + ': HTTP ' + resp.status + ' - ' + urls[u].split('items/')[1]);
          continue;
        }

        var json = await resp.json();
        var items = null;

        if (Array.isArray(json)) items = json;
        else if (json.data && Array.isArray(json.data)) items = json.data;
        else if (json.items && Array.isArray(json.items)) items = json.items;
        else if (json.results && Array.isArray(json.results)) items = json.results;
        else {
          Object.keys(json).forEach(function(k) {
            if (!items && Array.isArray(json[k]) && json[k].length > 0 && json[k][0].id) {
              items = json[k];
            }
          });
        }

        if (items && items.length > 0) {
          allItems = allItems.concat(items);
          workingUrl = urls[u];
          console.log('  Pagina ' + page + ': ' + items.length + ' items (total: ' + allItems.length + ')');
          keepGoing = items.length >= PAGE_SIZE;
          page++;
          success = true;
          break;
        } else {
          console.log('  Intento ' + (u + 1) + ': respuesta vacia');
        }
      } catch (e) {
        console.log('  Intento ' + (u + 1) + ' error: ' + e.message);
      }
    }

    if (!success) {
      if (allItems.length === 0) {
        console.error('No se pudo descargar el listado de items.');
        console.log('');
        console.log('Headers enviados:');
        Object.keys(myHeaders).forEach(function(k) {
          if (k === 'authorization') console.log('  ' + k + ': bearer ...(token presente)');
          else console.log('  ' + k + ': ' + myHeaders[k]);
        });
        alert('Error: No se pudo descargar el listado. Revisa la consola.');
        return;
      }
      keepGoing = false;
    }
  }

  console.log('Total items en listado: ' + allItems.length);

  // Check if items already have EANs
  var hasEans = allItems[0] && allItems[0].eans !== undefined;
  var finalData;

  if (hasEans) {
    console.log('');
    console.log('[PASO 2/3] El listado ya incluye EANs!');
    finalData = allItems;
  } else {
    console.log('');
    console.log('[PASO 2/3] Descargando EAN de ' + allItems.length + ' piezas...');
    console.log('Tiempo estimado: 3-5 minutos. No cierres la pestana.');

    finalData = [];
    var dn = 0;
    var errors = 0;
    var startTime = Date.now();

    for (var i = 0; i < allItems.length; i += BATCH) {
      var batch = allItems.slice(i, i + BATCH);
      var promises = batch.map(function(item) {
        return fetch(BASE + 'readOne?id=' + item.id, {
          method: 'GET',
          headers: myHeaders,
          credentials: 'include'
        })
        .then(function(r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.json();
        })
        .then(function(j) { dn++; return j.data || j; })
        .catch(function(e) {
          dn++;
          errors++;
          return { code: item.code || item.itemCode || '', name: item.name || item.itemName || '', eans: [] };
        });
      });

      var results = await Promise.all(promises);
      finalData = finalData.concat(results);

      if (dn % 100 === 0 || dn === allItems.length) {
        var pct = Math.round(dn / allItems.length * 100);
        var elapsed = Math.round((Date.now() - startTime) / 1000);
        var eta = dn > 0 ? Math.round(elapsed / dn * (allItems.length - dn)) : 0;
        console.log('  ' + dn + '/' + allItems.length + ' (' + pct + '%) - ~' + eta + 's restantes');
      }

      if (i + BATCH < allItems.length) {
        await new Promise(function(r) { setTimeout(r, DELAY); });
      }
    }

    var totalSec = Math.round((Date.now() - startTime) / 1000);
    console.log('  Completado en ' + totalSec + ' segundos.');
    if (errors > 0) console.log('  ' + errors + ' items con error.');
  }

  // STEP 3: Generate CSV
  console.log('');
  console.log('[PASO 3/3] Generando CSV...');

  var csv = '﻿Part No;Description;EAN Code;Group\n';
  var conEan = 0, sinEan = 0;

  for (var i = 0; i < finalData.length; i++) {
    var item = finalData[i];
    var code = String(item.code || '').replace(/"/g, '""');
    var nm = String(item.name || '').replace(/"/g, '""');
    var group = String(item.groupName || '').replace(/"/g, '""');
    var eans = item.eans || [];
    var eanStr = Array.isArray(eans) ? eans.join(', ') : String(eans || '');
    if (eanStr.length > 0) conEan++; else sinEan++;
    csv += '"' + code + '";"' + nm + '";"' + eanStr + '";"' + group + '"\n';
  }

  var blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
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

  alert('Completado!\n\nTotal piezas: ' + finalData.length + '\nCon EAN: ' + conEan + '\nSin EAN: ' + sinEan + '\n\nCSV descargado.');
})();
