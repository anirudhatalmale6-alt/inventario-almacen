(async function() {
  var BATCH_SIZE = 15;
  var DELAY_MS = 150;
  var API_BASE = 'https://de.fsm.cloud.sap/master-data-management-v2/portal/items/';

  console.log('=== SAP - Extractor de EAN Codes v9 ===');
  console.log('Estrategia: busqueda por prefijos para superar limite de 1000');
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

  var seenIds = {};
  var allItems = [];

  function addItems(items) {
    var added = 0;
    for (var i = 0; i < items.length; i++) {
      var id = items[i].id;
      if (id && !seenIds[id]) {
        seenIds[id] = true;
        allItems.push(items[i]);
        added++;
      }
    }
    return added;
  }

  async function searchItems(query) {
    var url = API_BASE + 'search?onlyActive=true&pageSize=1000&page=1&searchQuery=' + encodeURIComponent(query);
    try {
      var resp = await fetch(url, {
        method: 'GET',
        headers: requestHeaders,
        credentials: 'include'
      });
      if (!resp.ok) return [];
      var json = await resp.json();
      if (Array.isArray(json)) return json;
      if (json.data && Array.isArray(json.data)) return json.data;
      if (json.items && Array.isArray(json.items)) return json.items;
      if (json.results && Array.isArray(json.results)) return json.results;
      var keys = Object.keys(json);
      for (var k = 0; k < keys.length; k++) {
        if (Array.isArray(json[keys[k]]) && json[keys[k]].length > 0) return json[keys[k]];
      }
    } catch(e) {}
    return [];
  }

  // --- STEP 1: Get items using search prefixes ---
  console.log('[PASO 1/3] Descargando listado de piezas por prefijos...');
  console.log('');

  // First: get the initial 1000 with empty search
  console.log('  Busqueda inicial (sin filtro)...');
  var initial = await searchItems('');
  var initialAdded = addItems(initial);
  console.log('  Resultado: ' + initial.length + ' items (' + initialAdded + ' nuevos, total: ' + allItems.length + ')');

  // Search by single digits and letters
  var prefixes1 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
  console.log('');
  console.log('  Buscando por prefijos (0-9, A-Z)...');

  for (var p = 0; p < prefixes1.length; p++) {
    var prefix = prefixes1[p];
    var items = await searchItems(prefix);
    var added = addItems(items);
    if (items.length > 0) {
      console.log('  "' + prefix + '": ' + items.length + ' items (' + added + ' nuevos, total: ' + allItems.length + ')');
    }

    // If this prefix returned 1000 items, it probably hit the limit - search deeper
    if (items.length >= 1000) {
      console.log('    -> Limite alcanzado, buscando sub-prefijos ' + prefix + '0-' + prefix + 'Z...');
      var subPrefixes = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
      for (var s = 0; s < subPrefixes.length; s++) {
        var subPrefix = prefix + subPrefixes[s];
        var subItems = await searchItems(subPrefix);
        var subAdded = addItems(subItems);
        if (subItems.length > 0 && subAdded > 0) {
          console.log('    "' + subPrefix + '": ' + subItems.length + ' items (' + subAdded + ' nuevos, total: ' + allItems.length + ')');
        }
        if (subItems.length >= 1000) {
          console.log('      -> Sub-limite alcanzado en "' + subPrefix + '", buscando ' + subPrefix + '0-' + subPrefix + '9...');
          for (var d = 0; d <= 9; d++) {
            var deepPrefix = subPrefix + d;
            var deepItems = await searchItems(deepPrefix);
            var deepAdded = addItems(deepItems);
            if (deepItems.length > 0 && deepAdded > 0) {
              console.log('      "' + deepPrefix + '": ' + deepItems.length + ' items (' + deepAdded + ' nuevos, total: ' + allItems.length + ')');
            }
            await new Promise(function(r) { setTimeout(r, DELAY_MS); });
          }
        }
        await new Promise(function(r) { setTimeout(r, DELAY_MS); });
      }
    }

    await new Promise(function(r) { setTimeout(r, DELAY_MS); });
  }

  console.log('');
  console.log('Total items unicos encontrados: ' + allItems.length);

  if (allItems.length === 0) {
    console.log('No se pudo descargar ningun item.');
    console.log('Es posible que el token haya expirado.');
    alert('Error: No se pudo descargar items. El token puede haber expirado.');
    return;
  }

  // --- STEP 2: Get EAN codes if needed ---
  var itemsHaveEans = allItems[0] && allItems[0].eans !== undefined;
  var finalItems;

  if (itemsHaveEans) {
    console.log('');
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
