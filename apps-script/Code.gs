/**
 * Google Apps Script - Backend para Inventario Almacén
 * Se despliega como Web App desde la hoja de cálculo del cliente.
 * La app Android se comunica con este script para leer/escribir datos.
 */

// Configuración
var SHEET_NAME = "report";
var HEADER_ROW = 2;       // Fila de encabezados
var DATA_START_ROW = 3;   // Primera fila de datos
var COL_PART_NO = 1;      // A
var COL_DESCRIPTION = 2;  // B
var COL_ITEM_GROUP = 3;   // C
var COL_IN_STOCK = 4;     // D
var COL_RESPONSIBLE = 5;  // E
var COL_BARCODE = 6;      // F
var COL_MIN_STOCK = 7;    // G

function doGet(e) {
  return handleRequest(e);
}

function doPost(e) {
  return handleRequest(e);
}

function handleRequest(e) {
  try {
    var action = e.parameter.action;
    var result;

    switch(action) {
      case "getProducts":
        result = getProducts();
        break;
      case "updateBarcode":
        result = updateBarcode(e.parameter.row, e.parameter.barcode);
        break;
      case "updateMinStock":
        result = updateMinStock(e.parameter.row, e.parameter.minStock);
        break;
      case "updateStock":
        result = updateStock(e.parameter.row, e.parameter.stock);
        break;
      case "addProduct":
        result = addProduct(e.parameter.partNo, e.parameter.description, e.parameter.itemGroup, e.parameter.barcode, e.parameter.minStock);
        break;
      case "ensureHeaders":
        result = ensureHeaders();
        break;
      case "ping":
        result = { status: "ok", message: "Conexión exitosa" };
        break;
      default:
        result = { error: "Acción no reconocida: " + action };
    }

    return ContentService.createTextOutput(JSON.stringify(result))
      .setMimeType(ContentService.MimeType.JSON);

  } catch(error) {
    return ContentService.createTextOutput(JSON.stringify({
      error: error.toString()
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

function getProducts() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  if (!sheet) {
    return { error: "No se encontró la pestaña '" + SHEET_NAME + "'" };
  }

  var lastRow = sheet.getLastRow();
  if (lastRow < DATA_START_ROW) {
    return { products: [] };
  }

  var range = sheet.getRange(DATA_START_ROW, 1, lastRow - DATA_START_ROW + 1, 7);
  var values = range.getValues();
  var products = [];

  for (var i = 0; i < values.length; i++) {
    var partNo = String(values[i][0]).trim();
    if (partNo === "" || partNo === "undefined") continue;

    products.push({
      partNo: partNo,
      description: String(values[i][1]).trim(),
      itemGroup: String(values[i][2]).trim(),
      inStock: parseInt(values[i][3]) || 0,
      responsible: String(values[i][4]).trim(),
      barcode: String(values[i][5]).trim() || "",
      minStock: parseInt(values[i][6]) || 1,
      sheetRow: i + DATA_START_ROW
    });
  }

  return { products: products, count: products.length };
}

function updateBarcode(row, barcode) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  row = parseInt(row);
  sheet.getRange(row, COL_BARCODE).setValue(barcode);
  return { status: "ok", message: "Código de barras actualizado en fila " + row };
}

function updateMinStock(row, minStock) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  row = parseInt(row);
  minStock = parseInt(minStock);
  sheet.getRange(row, COL_MIN_STOCK).setValue(minStock);
  return { status: "ok", message: "Stock mínimo actualizado en fila " + row };
}

function updateStock(row, stock) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  row = parseInt(row);
  stock = parseInt(stock);
  sheet.getRange(row, COL_IN_STOCK).setValue(stock);
  return { status: "ok", message: "Stock actualizado en fila " + row };
}

function addProduct(partNo, description, itemGroup, barcode, minStock) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  var lastRow = sheet.getLastRow();
  var newRow = lastRow + 1;

  sheet.getRange(newRow, COL_PART_NO).setValue(partNo || "");
  sheet.getRange(newRow, COL_DESCRIPTION).setValue(description || "");
  sheet.getRange(newRow, COL_ITEM_GROUP).setValue(itemGroup || "");
  sheet.getRange(newRow, COL_IN_STOCK).setValue(0);
  sheet.getRange(newRow, COL_RESPONSIBLE).setValue("");
  sheet.getRange(newRow, COL_BARCODE).setValue(barcode || "");
  sheet.getRange(newRow, COL_MIN_STOCK).setValue(parseInt(minStock) || 1);

  return {
    status: "ok",
    message: "Producto creado en fila " + newRow,
    sheetRow: newRow
  };
}

function ensureHeaders() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  var headerF = sheet.getRange(HEADER_ROW, COL_BARCODE).getValue();
  var headerG = sheet.getRange(HEADER_ROW, COL_MIN_STOCK).getValue();

  if (String(headerF).trim() !== "Barcode") {
    sheet.getRange(HEADER_ROW, COL_BARCODE).setValue("Barcode");
  }
  if (String(headerG).trim() !== "Min Stock") {
    sheet.getRange(HEADER_ROW, COL_MIN_STOCK).setValue("Min Stock");
  }

  return { status: "ok", message: "Encabezados verificados" };
}
