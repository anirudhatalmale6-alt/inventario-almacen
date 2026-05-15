(async function() {
  var BATCH_SIZE = 15;
  var DELAY_MS = 100;
  var PAGE_SIZE = 1000;
  var MAX_PAGES = 10;
  var API_BASE = 'https://de.fsm.cloud.sap/master-data-management-v2/portal/items/';

  console.log('=== SAP - Extractor de EAN Codes v8 ===');
  console.log('Limite de paginas: ' + MAX_PAGES + ' (max ' + (MAX_PAGES * PAGE_SIZE) + ' items)');
  console.log('');

  var requestHeaders = {
    'accept': 'application/json, text/plain, */*',
    'authorization': 'bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImNsb3VkLWF1dGhlbnRpY2F0aW9uLXNlcnZpY2UtZGUifQ.eyJ0ZW5hbnRfaWQiOiIwMDAwMDAwMDA3NDA4MTA4OTYiLCJhdXRoX3R5cGUiOiJQQVNTV09SRCIsInVzZXJfZW1haWwiOiJlZHVhcmRvamVzdXMucGVyZXpAZnJlc2VuaXVzbWVkaWNhbGNhcmUuY29tIiwidXNlcl9uYW1lIjoiRk1DL2VkdWFyZG9qZXN1cy5wZXJleiIsInJ0aSI6ImNUczdvVnllUmhwNXRyR2tfSTNFbU84aHpvTSIsImF1dGhvcml0aWVzIjpbIlVTRVIiLCJTVVBQTEVNRU5UQUxfU0VSVklDRVMiXSwiY2xpZW50X2lkIjoic2hlbGwiLCJ1c2VyX3V1aWQiOm51bGwsImNvbXBhbmllcyI6W3siaWQiOjExMDgxMCwibmFtZSI6IlAxMV9GU01fU1AwMSIsImNsaWVudElkZW50aWZpZXIiOiJQUk9BWElBX0NPTl9FUlBfU0FQX0VDQyIsImRlc2NyaXB0aW9uIjoiUDExIEZNRSBTcGFpbiIsInN0cmljdEVuY3J5cHRpb25Qb2xpY3kiOmZhbHNlLCJwZXJtaXNzaW9uR3JvdXBJZCI6NDkwMjgsInBlcnNvbklkIjoiMmQ5MjQwNTdiOWU1NDFlNWJjMDQ5ZjJkODA2ZjlkMDkifV0sImFjY291bnRfaWQiOjg3Njg2LCJ1c2VyX2lkIjoxMDgzNTQwLCJwZXJtaXNzaW9uX2dyb3VwX2lkIjpudWxsLCJleHAiOjE3Nzg5Mzc2MDIsInVzZXIiOiJlZHVhcmRvamVzdXMucGVyZXoiLCJqdGkiOiJOYmRISkpZU3JIcEEybWgxaEt6MGhhdjAtNDAiLCJhY2NvdW50IjoiRk1DIn0.IWZrDWddImZ6abubiGFj6FVzJrcmCSm7LoYV4QKyhyg2dknDfk-AUb3ZZhdc9HEUy8fZF1yK6iMoO6W9a-v-K8XDXSWVEh5QvQMRmP8wu9kw5FUpn2bbZR53ztNUiE89ujRWUSgQZ2UylOfdLDdvwBOIJ3v8saXDYbmrXbL9pgBSIG6ugg22ewYmWj5fmqo4EQsi6vOeQq6gONa9dStx7KpdspmWA5WUVDeeIqwO0deBwXC9v20PaaekSY8C_vqVKILFE-OKt88kDNy5_f4FqX0hFJ4d9KPaBApBTFsrGbNr-geel-bDNu2ovvggIP4J45On5d7foTzIAEOVd13Ujw',
    'x-client-id': 'master-data-management-v2',
    'x-client-version': '0.34.0-rc2',
    'x-cloud-account-id': '87686',
    'x-cloud-account-name': 'FMC',
    'x-cloud-company-id': '110810',
    'x-cloud-company-name': 'P11_FSM_SP01',
    'x-cloud-host': 'de.fsm.cloud.sap',
    'x-cloud-user-id': '1083540',
    'x-cloud-user-name': 'eduardojesus.perez'
  };

  console.log('Credenciales configuradas.');
  console.log('');

  // --- STEP 1: Download all items (with page limit and deduplication) ---
  console.log('[PASO 1/3] Descargando listado de piezas...');
  var allItems = [];
  var seenIds = {};
  var currentPage = 1;
  var hasMorePages = true;
  var successUrl = '';
  var duplicatesSkipped = 0;

  while (hasMorePages && currentPage <= MAX_PAGES) {
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
          var newItems = 0;
          for (var pi = 0; pi < pageItems.length; pi++) {
            var itemId = pageItems[pi].id;
            if (itemId && seenIds[itemId]) {
              duplicatesSkipped++;
            } else {
              if (itemId) seenIds[itemId] = true;
              allItems.push(pageItems[pi]);
              newItems++;
            }
          }
          successUrl = urlsToTry[urlIdx];
          console.log('  Pagina ' + currentPage + ': ' + pageItems.length + ' items (' + newItems + ' nuevos, total unico: ' + allItems.length + ')');

          if (newItems === 0) {
            console.log('  Pagina sin items nuevos - fin de datos reales.');
            hasMorePages = false;
          } else {
            hasMorePages = pageItems.length >= PAGE_SIZE;
          }
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
        console.log('No se pudo descargar el listado.');
        console.log('Es posible que el token haya expirado.');
        console.log('Necesitas hacer "Copy as fetch" de un readOne nuevo y enviarmelo.');
        alert('Error: No se pudo descargar el listado. El token puede haber expirado.');
        return;
      }
      hasMorePages = false;
    }
  }

  if (currentPage > MAX_PAGES) {
    console.log('  Limite de ' + MAX_PAGES + ' paginas alcanzado.');
  }
  if (duplicatesSkipped > 0) {
    console.log('  ' + duplicatesSkipped + ' items duplicados eliminados.');
  }
  console.log('Total items unicos: ' + allItems.length);

  // --- STEP 2: Get EAN codes ---
  var itemsHaveEans = allItems[0] && allItems[0].eans !== undefined;
  var finalItems;

  if (itemsHaveEans) {
    console.log('[PASO 2/3] El listado ya incluye EANs!');
    finalItems = allItems;
  } else {
    console.log('');
    console.log('[PASO 2/3] Descargando EAN de ' + allItems.length + ' piezas...');
    var estMin = Math.ceil(allItems.length / BATCH_SIZE * DELAY_MS / 60000);
    console.log('Estimado: ' + estMin + '-' + (estMin * 2) + ' minutos. No cierres la pestana.');

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
        .then(function(jsonResp) {
          completedCount++;
          return jsonResp.data || jsonResp;
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

  // --- STEP 3: Generate CSV ---
  console.log('');
  console.log('[PASO 3/3] Generando CSV...');

  var csvContent = '﻿' + 'Part No;Description;EAN Code;Group\n';
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
