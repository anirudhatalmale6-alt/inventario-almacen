(async function() {
  var BATCH_SIZE = 15;
  var DELAY_MS = 100;
  var PAGE_SIZE = 1000;
  var API_BASE = 'https://de.fsm.cloud.sap/master-data-management-v2/portal/items/';

  console.log('=== SAP - Extractor de EAN Codes v6 ===');
  console.log('');

  // --- STEP 1: Find auth token ---
  var token = null;
  var accountId = '';
  var accountName = '';
  var companyId = '';
  var companyName = '';
  var cloudUserId = '';
  var cloudUserName = '';

  console.log('Buscando token de autorizacion...');

  // Search localStorage
  for (var i = 0; i < localStorage.length; i++) {
    var storageKey = localStorage.key(i);
    var storageVal = localStorage.getItem(storageKey);
    if (!storageVal) continue;
    if (storageVal.indexOf('eyJ') === 0 && storageVal.split('.').length === 3 && storageVal.length > 100) {
      token = storageVal;
      console.log('  Token en localStorage [' + storageKey + ']');
      break;
    }
    try {
      var parsed = JSON.parse(storageVal);
      if (typeof parsed === 'object' && parsed !== null) {
        var keys = Object.keys(parsed);
        for (var j = 0; j < keys.length; j++) {
          var v = parsed[keys[j]];
          if (typeof v === 'string' && v.indexOf('eyJ') === 0 && v.split('.').length === 3 && v.length > 100) {
            token = v;
            console.log('  Token en localStorage [' + storageKey + '.' + keys[j] + ']');
            break;
          }
        }
      }
    } catch(e) {}
    if (token) break;
  }

  // Search sessionStorage
  if (!token) {
    for (var i = 0; i < sessionStorage.length; i++) {
      var storageKey = sessionStorage.key(i);
      var storageVal = sessionStorage.getItem(storageKey);
      if (!storageVal) continue;
      if (storageVal.indexOf('eyJ') === 0 && storageVal.split('.').length === 3 && storageVal.length > 100) {
        token = storageVal;
        console.log('  Token en sessionStorage [' + storageKey + ']');
        break;
      }
      try {
        var parsed = JSON.parse(storageVal);
        if (typeof parsed === 'object' && parsed !== null) {
          var keys = Object.keys(parsed);
          for (var j = 0; j < keys.length; j++) {
            var v = parsed[keys[j]];
            if (typeof v === 'string' && v.indexOf('eyJ') === 0 && v.split('.').length === 3 && v.length > 100) {
              token = v;
              console.log('  Token en sessionStorage [' + storageKey + '.' + keys[j] + ']');
              break;
            }
          }
        }
      } catch(e) {}
      if (token) break;
    }
  }

  // Search cookies
  if (!token) {
    var cookieList = document.cookie.split(';');
    for (var c = 0; c < cookieList.length; c++) {
      var cookieParts = cookieList[c].trim().split('=');
      var cookieVal = cookieParts.slice(1).join('=');
      if (cookieVal && cookieVal.indexOf('eyJ') === 0 && cookieVal.split('.').length === 3) {
        token = cookieVal;
        console.log('  Token en cookie [' + cookieParts[0] + ']');
        break;
      }
    }
  }

  // Intercept next request
  if (!token) {
    console.log('  No encontrado en almacenamiento.');
    console.log('');
    console.log('>>> HAZ CLIC EN UNA PIEZA DEL LISTADO o escribe en la barra de busqueda <<<');
    console.log('');

    var interceptResult = await new Promise(function(resolve) {
      var isDone = false;
      var timeout = setTimeout(function() {
        if (!isDone) { isDone = true; resolve(null); }
      }, 60000);

      var originalFetch = window.fetch;
      window.fetch = function(input, init) {
        var result = originalFetch.apply(this, arguments);
        if (isDone) return result;

        var headers = null;
        if (init && init.headers) headers = init.headers;
        else if (input instanceof Request) headers = input.headers;

        if (headers) {
          var authValue = '';
          if (headers instanceof Headers) authValue = headers.get('authorization') || '';
          else if (typeof headers === 'object') authValue = headers.authorization || headers.Authorization || '';

          if (authValue && authValue.toLowerCase().indexOf('bearer') === 0) {
            isDone = true;
            window.fetch = originalFetch;
            clearTimeout(timeout);
            var extracted = {};
            if (headers instanceof Headers) headers.forEach(function(v, k) { extracted[k] = v; });
            else Object.keys(headers).forEach(function(k) { extracted[k] = headers[k]; });
            resolve(extracted);
          }
        }
        return result;
      };

      var origXhrOpen = XMLHttpRequest.prototype.open;
      var origXhrSetHeader = XMLHttpRequest.prototype.setRequestHeader;
      var origXhrSend = XMLHttpRequest.prototype.send;

      XMLHttpRequest.prototype.setRequestHeader = function(headerName, headerValue) {
        if (!this._capturedHeaders) this._capturedHeaders = {};
        this._capturedHeaders[headerName.toLowerCase()] = headerValue;
        return origXhrSetHeader.apply(this, arguments);
      };

      XMLHttpRequest.prototype.send = function() {
        if (!isDone && this._capturedHeaders) {
          var authVal = this._capturedHeaders['authorization'] || '';
          if (authVal.toLowerCase().indexOf('bearer') === 0) {
            isDone = true;
            XMLHttpRequest.prototype.send = origXhrSend;
            XMLHttpRequest.prototype.setRequestHeader = origXhrSetHeader;
            XMLHttpRequest.prototype.open = origXhrOpen;
            clearTimeout(timeout);
            resolve(this._capturedHeaders);
          }
        }
        return origXhrSend.apply(this, arguments);
      };
    });

    if (interceptResult) {
      var authHeader = interceptResult.authorization || interceptResult.Authorization || '';
      token = authHeader.replace(/^bearer\s+/i, '');
      Object.keys(interceptResult).forEach(function(k) {
        var kLower = k.toLowerCase();
        if (kLower === 'x-cloud-account-id') accountId = interceptResult[k];
        if (kLower === 'x-cloud-account-name') accountName = interceptResult[k];
        if (kLower === 'x-cloud-company-id') companyId = interceptResult[k];
        if (kLower === 'x-cloud-company-name') companyName = interceptResult[k];
        if (kLower === 'x-cloud-user-id') cloudUserId = interceptResult[k];
        if (kLower === 'x-cloud-user-name') cloudUserName = interceptResult[k];
      });
    }
  }

  // Manual prompt as last resort
  if (!token) {
    console.log('');
    console.log('No se detecto automaticamente.');
    console.log('En Network, clic derecho sobre readOne > Copy > Copy as fetch');
    var manualInput = prompt('Pega aqui el resultado de "Copy as fetch":');
    if (manualInput) {
      var tokenMatch = manualInput.match(/bearer\s+([A-Za-z0-9_.-]+)/i);
      if (tokenMatch) token = tokenMatch[1];
      var matches = {
        'x-cloud-account-id': /x-cloud-account-id['":\s]+(\d+)/i,
        'x-cloud-account-name': /x-cloud-account-name['":\s]+([^'"]+)/i,
        'x-cloud-company-id': /x-cloud-company-id['":\s]+(\d+)/i,
        'x-cloud-company-name': /x-cloud-company-name['":\s]+([^'"]+)/i,
        'x-cloud-user-id': /x-cloud-user-id['":\s]+(\d+)/i,
        'x-cloud-user-name': /x-cloud-user-name['":\s]+([^'"]+)/i
      };
      Object.keys(matches).forEach(function(key) {
        var m = manualInput.match(matches[key]);
        if (m) {
          var val = m[1].trim().replace(/['"]/g, '');
          if (key === 'x-cloud-account-id') accountId = val;
          if (key === 'x-cloud-account-name') accountName = val;
          if (key === 'x-cloud-company-id') companyId = val;
          if (key === 'x-cloud-company-name') companyName = val;
          if (key === 'x-cloud-user-id') cloudUserId = val;
          if (key === 'x-cloud-user-name') cloudUserName = val;
        }
      });
    }
  }

  if (!token) {
    alert('No se pudo obtener el token de autorizacion.');
    return;
  }

  // Decode JWT to get account info if not already captured
  if (!accountId) {
    try {
      var jwtParts = token.split('.');
      var base64 = jwtParts[1].replace(/-/g, '+').replace(/_/g, '/');
      var jwtPayload = JSON.parse(atob(base64));
      accountId = String(jwtPayload.account_id || '');
      accountName = jwtPayload.account || '';
      cloudUserId = String(jwtPayload.user_id || '');
      cloudUserName = jwtPayload.user || '';
      if (jwtPayload.companies && jwtPayload.companies.length > 0) {
        companyId = String(jwtPayload.companies[0].id || '');
        companyName = jwtPayload.companies[0].name || '';
      }
    } catch(decodeErr) {
      console.log('  Aviso: No se pudo decodificar JWT');
    }
  }

  // Build headers
  var requestHeaders = {
    'accept': 'application/json, text/plain, */*',
    'authorization': 'bearer ' + token,
    'x-client-id': 'master-data-management-v2',
    'x-client-version': '0.34.0-rc2',
    'x-cloud-host': 'de.fsm.cloud.sap'
  };
  if (accountId) requestHeaders['x-cloud-account-id'] = accountId;
  if (accountName) requestHeaders['x-cloud-account-name'] = accountName;
  if (companyId) requestHeaders['x-cloud-company-id'] = companyId;
  if (companyName) requestHeaders['x-cloud-company-name'] = companyName;
  if (cloudUserId) requestHeaders['x-cloud-user-id'] = cloudUserId;
  if (cloudUserName) requestHeaders['x-cloud-user-name'] = cloudUserName;

  console.log('Token OK! Account: ' + accountName + ', Company: ' + companyName);
  console.log('');

  // --- STEP 2: Download all items ---
  console.log('[PASO 1/3] Descargando listado de piezas...');
  var allItems = [];
  var currentPage = 1;
  var hasMorePages = true;
  var successUrl = '';

  while (hasMorePages) {
    var urlsToTry;
    if (successUrl) {
      urlsToTry = [successUrl.replace(/page=\d+/, 'page=' + currentPage)];
    } else {
      urlsToTry = [
        API_BASE + 'search?onlyActive=true&pageSize=' + PAGE_SIZE + '&page=' + currentPage + '&searchQuery=',
        API_BASE + 'search?pageSize=' + PAGE_SIZE + '&page=' + currentPage + '&searchQuery=',
        API_BASE + 'readAll?page=' + currentPage + '&pageSize=' + PAGE_SIZE + '&searchQuery=',
        API_BASE + 'readAll?page=' + currentPage + '&pageSize=' + PAGE_SIZE
      ];
    }

    var pageSuccess = false;
    for (var urlIdx = 0; urlIdx < urlsToTry.length; urlIdx++) {
      try {
        var listResp = await fetch(urlsToTry[urlIdx], {
          method: 'GET',
          headers: requestHeaders,
          credentials: 'include'
        });

        if (!listResp.ok) {
          console.log('  Intento ' + (urlIdx + 1) + ': HTTP ' + listResp.status);
          continue;
        }

        var listJson = await listResp.json();
        var pageItems = null;

        if (Array.isArray(listJson)) {
          pageItems = listJson;
        } else if (listJson.data && Array.isArray(listJson.data)) {
          pageItems = listJson.data;
        } else if (listJson.items && Array.isArray(listJson.items)) {
          pageItems = listJson.items;
        } else if (listJson.results && Array.isArray(listJson.results)) {
          pageItems = listJson.results;
        } else {
          var jsonKeys = Object.keys(listJson);
          for (var ki = 0; ki < jsonKeys.length; ki++) {
            var possibleArray = listJson[jsonKeys[ki]];
            if (Array.isArray(possibleArray) && possibleArray.length > 0) {
              pageItems = possibleArray;
              break;
            }
          }
        }

        if (pageItems && pageItems.length > 0) {
          allItems = allItems.concat(pageItems);
          successUrl = urlsToTry[urlIdx];
          console.log('  Pagina ' + currentPage + ': ' + pageItems.length + ' items (total: ' + allItems.length + ')');
          hasMorePages = pageItems.length >= PAGE_SIZE;
          currentPage++;
          pageSuccess = true;
          break;
        }
      } catch(fetchErr) {
        console.log('  Intento ' + (urlIdx + 1) + ' error: ' + fetchErr.message);
      }
    }

    if (!pageSuccess) {
      if (allItems.length === 0) {
        console.log('');
        console.log('No se pudo descargar el listado. Headers enviados:');
        Object.keys(requestHeaders).forEach(function(hk) {
          if (hk === 'authorization') console.log('  ' + hk + ': bearer ...(presente)');
          else console.log('  ' + hk + ': ' + requestHeaders[hk]);
        });
        alert('Error: No se pudo descargar el listado. Revisa la consola.');
        return;
      }
      hasMorePages = false;
    }
  }

  console.log('Total items en listado: ' + allItems.length);

  // --- STEP 3: Get EAN codes ---
  var itemsHaveEans = allItems[0] && allItems[0].eans !== undefined;
  var finalItems;

  if (itemsHaveEans) {
    console.log('[PASO 2/3] El listado ya incluye EANs!');
    finalItems = allItems;
  } else {
    console.log('');
    console.log('[PASO 2/3] Descargando EAN de ' + allItems.length + ' piezas...');
    console.log('Estimado: 3-5 minutos. No cierres la pestana.');

    finalItems = [];
    var completedCount = 0;
    var errorCount = 0;
    var startTimestamp = new Date().getTime();

    for (var batchStart = 0; batchStart < allItems.length; batchStart += BATCH_SIZE) {
      var currentBatch = allItems.slice(batchStart, batchStart + BATCH_SIZE);
      var batchPromises = currentBatch.map(function(batchItem) {
        return fetch(API_BASE + 'readOne?id=' + batchItem.id, {
          method: 'GET',
          headers: requestHeaders,
          credentials: 'include'
        })
        .then(function(r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.json();
        })
        .then(function(jsonData) {
          completedCount++;
          return jsonData.data || jsonData;
        })
        .catch(function(err) {
          completedCount++;
          errorCount++;
          return {
            code: batchItem.code || batchItem.itemCode || '',
            name: batchItem.name || batchItem.itemName || '',
            eans: []
          };
        });
      });

      var batchResults = await Promise.all(batchPromises);
      finalItems = finalItems.concat(batchResults);

      if (completedCount % 100 === 0 || completedCount === allItems.length) {
        var percent = Math.round(completedCount / allItems.length * 100);
        var elapsedSec = Math.round((new Date().getTime() - startTimestamp) / 1000);
        var etaSec = completedCount > 0 ? Math.round(elapsedSec / completedCount * (allItems.length - completedCount)) : 0;
        console.log('  ' + completedCount + '/' + allItems.length + ' (' + percent + '%) ~' + etaSec + 's restantes');
      }

      if (batchStart + BATCH_SIZE < allItems.length) {
        await new Promise(function(waitResolve) { setTimeout(waitResolve, DELAY_MS); });
      }
    }

    var totalSeconds = Math.round((new Date().getTime() - startTimestamp) / 1000);
    console.log('  Completado en ' + totalSeconds + ' segundos.');
    if (errorCount > 0) console.log('  ' + errorCount + ' items con error.');
  }

  // --- STEP 4: Generate CSV ---
  console.log('');
  console.log('[PASO 3/3] Generando CSV...');

  var BOM = '﻿';
  var csvContent = BOM + 'Part No;Description;EAN Code;Group\n';
  var withEanCount = 0;
  var withoutEanCount = 0;

  for (var ri = 0; ri < finalItems.length; ri++) {
    var rowItem = finalItems[ri];
    var itemCode = String(rowItem.code || '').replace(/"/g, '""');
    var itemName = String(rowItem.name || '').replace(/"/g, '""');
    var itemGroup = String(rowItem.groupName || '').replace(/"/g, '""');
    var itemEans = rowItem.eans || [];
    var eanString = Array.isArray(itemEans) ? itemEans.join(', ') : '';

    if (eanString.length > 0) withEanCount++;
    else withoutEanCount++;

    csvContent += '"' + itemCode + '";"' + itemName + '";"' + eanString + '";"' + itemGroup + '"\n';
  }

  var csvBlob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  var downloadLink = document.createElement('a');
  downloadLink.href = URL.createObjectURL(csvBlob);
  downloadLink.download = 'SAP_Piezas_EAN.csv';
  document.body.appendChild(downloadLink);
  downloadLink.click();
  document.body.removeChild(downloadLink);

  console.log('');
  console.log('========================================');
  console.log('  EXTRACCION COMPLETADA');
  console.log('========================================');
  console.log('  Total piezas: ' + finalItems.length);
  console.log('  Con codigo EAN: ' + withEanCount);
  console.log('  Sin codigo EAN: ' + withoutEanCount);

  alert('Completado!\n\nTotal: ' + finalItems.length + '\nCon EAN: ' + withEanCount + '\nSin EAN: ' + withoutEanCount + '\n\nCSV descargado.');
})();
