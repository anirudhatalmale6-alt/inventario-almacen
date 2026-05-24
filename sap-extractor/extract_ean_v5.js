(async function() {
  var BATCH = 15;
  var DELAY = 100;
  var PAGE_SIZE = 1000;
  var BASE = 'https://de.fsm.cloud.sap/master-data-management-v2/portal/items/';

  console.log('=== SAP - Extractor de EAN Codes v5 ===');
  console.log('');

  // STEP 1: Find auth token - try multiple methods
  var token = null;
  var accountId = '';
  var accountName = '';
  var companyId = '';
  var companyName = '';
  var userId = '';
  var userName = '';

  // Method 1: Search localStorage and sessionStorage for JWT tokens
  console.log('Buscando token de autorizacion...');
  var stores = [localStorage, sessionStorage];
  for (var s = 0; s < stores.length && !token; s++) {
    var store = stores[s];
    for (var i = 0; i < store.length; i++) {
      var key = store.key(i);
      var val = store.getItem(key);
      if (!val) continue;

      // Direct JWT token (three base64 parts separated by dots)
      if (val.indexOf('eyJ') === 0 && val.split('.').length === 3 && val.length > 100) {
        token = val;
        console.log('  Token encontrado en ' + (s === 0 ? 'localStorage' : 'sessionStorage') + ' [' + key + ']');
        break;
      }

      // Token inside JSON object
      try {
        var obj = JSON.parse(val);
        if (typeof obj === 'object' && obj !== null) {
          var tokenKeys = ['access_token', 'token', 'jwt', 'id_token', 'accessToken', 'bearer', 'auth_token'];
          for (var t = 0; t < tokenKeys.length; t++) {
            var tv = obj[tokenKeys[t]];
            if (tv && typeof tv === 'string' && tv.indexOf('eyJ') === 0 && tv.split('.').length === 3) {
              token = tv;
              console.log('  Token encontrado en ' + (s === 0 ? 'localStorage' : 'sessionStorage') + ' [' + key + '.' + tokenKeys[t] + ']');
              break;
            }
          }
          if (token) break;

          // Deep search one level
          Object.keys(obj).forEach(function(k2) {
            if (token) return;
            var v2 = obj[k2];
            if (typeof v2 === 'string' && v2.indexOf('eyJ') === 0 && v2.split('.').length === 3 && v2.length > 100) {
              token = v2;
              console.log('  Token encontrado en ' + (s === 0 ? 'localStorage' : 'sessionStorage') + ' [' + key + '.' + k2 + ']');
            }
          });
        }
      } catch(e) {}
    }
  }

  // Method 2: Check cookies
  if (!token) {
    var cookies = document.cookie.split(';');
    for (var c = 0; c < cookies.length; c++) {
      var parts = cookies[c].trim().split('=');
      var cval = parts.slice(1).join('=');
      if (cval && cval.indexOf('eyJ') === 0 && cval.split('.').length === 3) {
        token = cval;
        console.log('  Token encontrado en cookie [' + parts[0] + ']');
        break;
      }
    }
  }

  // Method 3: Intercept next request (fetch + XHR with Request object fix)
  if (!token) {
    console.log('  No se encontro en almacenamiento. Intentando interceptar...');
    console.log('');
    console.log('>>> HAZ CLIC EN UNA PIEZA DEL LISTADO (o escribe algo en la barra de busqueda) <<<');
    console.log('');

    var intercepted = await new Promise(function(resolve) {
      var done = false;
      var timer = setTimeout(function() { if (!done) { done = true; resolve(null); } }, 60000);

      // Intercept fetch - handle both plain objects and Request objects
      var origFetch = window.fetch;
      window.fetch = function(input, init) {
        var result = origFetch.apply(this, arguments);
        if (done) return result;

        var h = null;
        if (init && init.headers) h = init.headers;
        else if (input instanceof Request) h = input.headers;

        if (h) {
          var authVal = '';
          if (h instanceof Headers) authVal = h.get('authorization') || '';
          else if (typeof h === 'object') authVal = h.authorization || h.Authorization || '';

          if (authVal && authVal.toLowerCase().indexOf('bearer') === 0) {
            done = true;
            window.fetch = origFetch;
            clearTimeout(timer);
            var out = {};
            if (h instanceof Headers) h.forEach(function(v, k) { out[k] = v; });
            else Object.keys(h).forEach(function(k) { out[k] = h[k]; });
            resolve(out);
          }
        }
        return result;
      };

      // Intercept XMLHttpRequest
      var origSend = XMLHttpRequest.prototype.send;
      var origOpen = XMLHttpRequest.prototype.open;
      var origSetH = XMLHttpRequest.prototype.setRequestHeader;

      XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
        if (!this._myHeaders) this._myHeaders = {};
        this._myHeaders[name.toLowerCase()] = value;
        return origSetH.apply(this, arguments);
      };

      XMLHttpRequest.prototype.send = function() {
        if (!done && this._myHeaders) {
          var auth = this._myHeaders['authorization'] || '';
          if (auth.toLowerCase().indexOf('bearer') === 0) {
            done = true;
            XMLHttpRequest.prototype.send = origSend;
            XMLHttpRequest.prototype.setRequestHeader = origSetH;
            XMLHttpRequest.prototype.open = origOpen;
            clearTimeout(timer);
            resolve(this._myHeaders);
          }
        }
        return origSend.apply(this, arguments);
      };
    });

    if (intercepted) {
      token = (intercepted.authorization || intercepted.Authorization || '').replace(/^bearer\s+/i, '');
      // Copy x-cloud headers
      Object.keys(intercepted).forEach(function(k) {
        var kl = k.toLowerCase();
        if (kl.indexOf('x-cloud-account-id') === 0) accountId = intercepted[k];
        if (kl.indexOf('x-cloud-account-name') === 0) accountName = intercepted[k];
        if (kl.indexOf('x-cloud-company-id') === 0) companyId = intercepted[k];
        if (kl.indexOf('x-cloud-company-name') === 0) companyName = intercepted[k];
        if (kl.indexOf('x-cloud-user-id') === 0) userId = intercepted[k];
        if (kl.indexOf('x-cloud-user-name') === 0) userName = intercepted[k];
      });
    }
  }

  // Method 4: Manual prompt
  if (!token) {
    console.log('');
    console.log('No se encontro el token automaticamente.');
    console.log('En la pestana Network, haz clic derecho sobre cualquier readOne > Copy > Copy as fetch');
    var pasted = prompt('Pega aqui el resultado de "Copy as fetch":');
    if (pasted) {
      var tokenMatch = pasted.match(/bearer\s+([A-Za-z0-9_.-]+)/i);
      if (tokenMatch) token = tokenMatch[1];

      var accountIdMatch = pasted.match(/x-cloud-account-id['":\s]+(\d+)/i);
      if (accountIdMatch) accountId = accountIdMatch[1];
      var accountNameMatch = pasted.match(/x-cloud-account-name['":\s]+([^'"]+)/i);
      if (accountNameMatch) accountName = accountNameMatch[1].trim().replace(/['"]/g, '');
      var companyIdMatch = pasted.match(/x-cloud-company-id['":\s]+(\d+)/i);
      if (companyIdMatch) companyId = companyIdMatch[1];
      var companyNameMatch = pasted.match(/x-cloud-company-name['":\s]+([^'"]+)/i);
      if (companyNameMatch) companyName = companyNameMatch[1].trim().replace(/['"]/g, '');
      var userIdMatch = pasted.match(/x-cloud-user-id['":\s]+(\d+)/i);
      if (userIdMatch) userId = userIdMatch[1];
      var userNameMatch = pasted.match(/x-cloud-user-name['":\s]+([^'"]+)/i);
      if (userNameMatch) userName = userNameMatch[1].trim().replace(/['"]/g, '');
    }
  }

  if (!token) {
    alert('No se pudo obtener el token de autorizacion.');
    return;
  }

  // Decode JWT to get account info if not captured from headers
  if (!accountId) {
    try {
      var payloadB64 = token.split('.')[1];
      var payload = JSON.parse(atob(payloadB64.replace(/-/g, '+').replace(/_/g, '/')));
      accountId = String(payload.account_id || '');
      accountName = payload.account || '';
      userId = String(payload.user_id || '');
      userName = payload.user || '';
      if (payload.companies && payload.companies.length > 0) {
        companyId = String(payload.companies[0].id || '');
        companyName = payload.companies[0].name || '';
      }
    } catch(e) {
      console.log('  No se pudo decodificar JWT: ' + e.message);
    }
  }

  // Build request headers
  var myH = {
    'accept': 'application/json, text/plain, */*',
    'authorization': 'bearer ' + token,
    'x-client-id': 'master-data-management-v2',
    'x-client-version': '0.34.0-rc2',
    'x-cloud-host': 'de.fsm.cloud.sap'
  };
  if (accountId) myH['x-cloud-account-id'] = accountId;
  if (accountName) myH['x-cloud-account-name'] = accountName;
  if (companyId) myH['x-cloud-company-id'] = companyId;
  if (companyName) myH['x-cloud-company-name'] = companyName;
  if (userId) myH['x-cloud-user-id'] = userId;
  if (userName) myH['x-cloud-user-name'] = userName;

  console.log('Token obtenido! Account: ' + accountName + ', Company: ' + companyName);
  console.log('');

  // STEP 2: Test readOne first to verify auth works
  console.log('Verificando conexion con la API...');
  try {
    var testResp = await fetch(BASE + 'search?onlyActive=true&pageSize=5&page=1&searchQuery=', {
      method: 'GET', headers: myH, credentials: 'include'
    });
    console.log('  search endpoint: HTTP ' + testResp.status);
    if (!testResp.ok) {
      var testResp2 = await fetch(BASE + 'readAll?page=1&pageSize=5&searchQuery=', {
        method: 'GET', headers: myH, credentials: 'include'
      });
      console.log('  readAll endpoint: HTTP ' + testResp2.status);
    }
  } catch(e) {
    console.log('  Error de conexion: ' + e.message);
  }

  // STEP 3: Get all items
  console.log('');
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
        BASE + 'readAll?page=' + page + '&pageSize=' + PAGE_SIZE
      ];
    }

    var success = false;
    for (var u = 0; u < urls.length; u++) {
      try {
        var resp = await fetch(urls[u], { method: 'GET', headers: myH, credentials: 'include' });
        if (!resp.ok) {
          console.log('  Intento ' + (u+1) + ': HTTP ' + resp.status);
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
            if (!items && Array.isArray(json[k]) && json[k].length > 0) items = json[k];
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
        }
      } catch(e) { console.log('  Intento ' + (u+1) + ' error: ' + e.message); }
    }
    if (!success) {
      if (allItems.length === 0) {
        console.log('');
        console.log('No se pudo descargar el listado. Informacion de depuracion:');
        console.log('  Headers:');
        Object.keys(myH).forEach(function(k) {
          console.log('    ' + k + ': ' + (k === 'authorization' ? 'bearer ...(presente)' : myH[k]));
        });
        alert('Error: No se pudo descargar el listado. Revisa la consola.');
        return;
      }
      keepGoing = false;
    }
  }

  console.log('Total items: ' + allItems.length);

  var hasEans = allItems[0] && allItems[0].eans !== undefined;
  var finalData;

  if (hasEans) {
    console.log('[PASO 2/3] Ya incluye EANs!');
    finalData = allItems;
  } else {
    console.log('');
    console.log('[PASO 2/3] Descargando EAN de ' + allItems.length + ' piezas...');
    console.log('Estimado: 3-5 min. No cierres la pestana.');
    finalData = [];
    var dn = 0, errors = 0, st = Date.now();
    for (var i = 0; i < allItems.length; i += BATCH) {
      var batch = allItems.slice(i, i + BATCH);
      var promises = batch.map(function(item) {
        return fetch(BASE + 'readOne?id=' + item.id, { method: 'GET', headers: myH, credentials: 'include' })
          .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
          .then(function(j) { dn++; return j.data || j; })
          .catch(function(e) { dn++; errors++; return {code: item.code||'', name: item.name||'', eans: []}; });
      });
      var results = await Promise.all(promises);
      finalData = finalData.concat(results);
      if (dn % 100 === 0 || dn === allItems.length) {
        var pct = Math.round(dn/allItems.length*100);
        var el = Math.round((Date.now()-st)/1000);
        var eta = dn > 0 ? Math.round(el/dn*(allItems.length-dn)) : 0;
        console.log('  ' + dn + '/' + allItems.length + ' (' + pct + '%) ~' + eta + 's');
      }
      if (i + BATCH < allItems.length) await new Promise(function(r) { setTimeout(r, DELAY); });
    }
    if (errors > 0) console.log('  ' + errors + ' items con error.');
  }

  console.log('');
  console.log('[PASO 3/3] Generando CSV...');
  var csv = '﻿Part No;Description;EAN Code;Group\n';
  var conEan = 0, sinEan = 0;
  for (var i = 0; i < finalData.length; i++) {
    var item = finalData[i];
    var code = String(item.code||'').replace(/"/g,'""');
    var nm = String(item.name||'').replace(/"/g,'""');
    var group = String(item.groupName||'').replace(/"/g,'""');
    var eans = item.eans || [];
    var eanStr = Array.isArray(eans) ? eans.join(', ') : '';
    if (eanStr.length > 0) conEan++; else sinEan++;
    csv += '"'+code+'";"'+nm+'";"'+eanStr+'";"'+group+'"\n';
  }
  var blob = new Blob([csv], {type:'text/csv;charset=utf-8;'});
  var a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'SAP_Piezas_EAN.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  console.log('=== COMPLETADO ===');
  console.log('Total: ' + finalData.length + ' | Con EAN: ' + conEan + ' | Sin EAN: ' + sinEan);
  alert('Completado!\n\nTotal: ' + finalData.length + '\nCon EAN: ' + conEan + '\nSin EAN: ' + sinEan + '\n\nCSV descargado.');
})();
