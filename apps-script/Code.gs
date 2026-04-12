/**
 * Google Apps Script - Backend para Inventario Almacen
 * Se despliega como Web App desde la hoja de calculo del cliente.
 * La app Android se comunica con este script para leer/escribir datos.
 *
 * Estructura de columnas:
 * A = Part No. | B = Description | C = Item Group
 * D = Stock Edu | E = Stock Min. | F = Barcode
 *
 * Fila 1 = Encabezados, Fila 2+ = Datos
 */

var SHEET_NAME = "report";
var HEADER_ROW = 1;
var DATA_START_ROW = 2;
var COL_PART_NO = 1;
var COL_DESCRIPTION = 2;
var COL_ITEM_GROUP = 3;
var COL_IN_STOCK = 4;
var COL_MIN_STOCK = 5;
var COL_BARCODE = 6;

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
        result = { status: "ok", message: "Conexion exitosa" };
        break;
      default:
        result = { error: "Accion no reconocida: " + action };
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
    return { error: "No se encontro la pestana '" + SHEET_NAME + "'" };
  }

  var lastRow = sheet.getLastRow();
  if (lastRow < DATA_START_ROW) {
    return { products: [] };
  }

  var range = sheet.getRange(DATA_START_ROW, 1, lastRow - DATA_START_ROW + 1, 6);
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
      minStock: parseInt(values[i][4]) || 1,
      barcode: String(values[i][5]).trim() || "",
      sheetRow: i + DATA_START_ROW
    });
  }

  return { products: products, count: products.length };
}

function updateBarcode(row, barcode) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  row = parseInt(row);
  sheet.getRange(row, COL_BARCODE).setValue(barcode);
  return { status: "ok", message: "Codigo de barras actualizado en fila " + row };
}

function updateMinStock(row, minStock) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  row = parseInt(row);
  minStock = parseInt(minStock);
  sheet.getRange(row, COL_MIN_STOCK).setValue(minStock);
  return { status: "ok", message: "Stock minimo actualizado en fila " + row };
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
  sheet.getRange(newRow, COL_MIN_STOCK).setValue(parseInt(minStock) || 1);
  sheet.getRange(newRow, COL_BARCODE).setValue(barcode || "");

  return {
    status: "ok",
    message: "Producto creado en fila " + newRow,
    sheetRow: newRow
  };
}

function ensureHeaders() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  var headerE = sheet.getRange(HEADER_ROW, COL_MIN_STOCK).getValue();
  var headerF = sheet.getRange(HEADER_ROW, COL_BARCODE).getValue();

  if (String(headerE).trim() !== "Stock Min.") {
    sheet.getRange(HEADER_ROW, COL_MIN_STOCK).setValue("Stock Min.");
  }
  if (String(headerF).trim() !== "Barcode") {
    sheet.getRange(HEADER_ROW, COL_BARCODE).setValue("Barcode");
  }

  return { status: "ok", message: "Encabezados verificados" };
}
