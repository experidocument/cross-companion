/**
 * Cross Companion → Google Doc webhook.
 *
 * Receives journal entries from the Cross Companion Android app and files each one as a
 * bullet under its category header (e.g. "🎬 Film & TV") in a Google Doc you own. Headers
 * that already exist are reused, so repeated syncs keep each category in one section.
 *
 * Setup: open your Doc → Extensions → Apps Script → replace everything with this file →
 * Deploy → New deployment → Web app (Execute as: Me, Who has access: Anyone) → copy the URL
 * into the app (Journal tab → settings).
 */

// Optional: paste a Doc ID (the long part of the Doc's URL) to write to a specific Doc.
// Leave empty when this script was opened from the Doc via Extensions → Apps Script.
const DOC_ID = '';

const SECTION = DocumentApp.ParagraphHeading.HEADING2;

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    // "entry" and "export" are payloads from older app versions.
    const entries = data.entries || (data.entry ? [data.entry] : []);
    if (!entries.length) throw new Error('No entries in payload.');

    const doc = DOC_ID ? DocumentApp.openById(DOC_ID) : DocumentApp.getActiveDocument();
    if (!doc) throw new Error('No Doc found: set DOC_ID or create the script from inside a Doc.');
    const body = doc.getBody();

    const groups = {};
    const order = [];
    entries.forEach(function (entry) {
      const category = String(entry.category || '💡 General').trim();
      if (!groups[category]) { groups[category] = []; order.push(category); }
      groups[category].push(entry);
    });
    order.forEach(function (category) { fileUnder_(body, category, groups[category]); });

    doc.saveAndClose();
    return json_({ ok: true, count: entries.length });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

/** Lets you open the web app URL in a browser to check the deployment is live. */
function doGet() {
  return json_({ ok: true, message: 'Cross Companion webhook is running.' });
}

function fileUnder_(body, category, entries) {
  let index = sectionEnd_(body, category);
  if (index < 0) {
    body.appendParagraph(category).setHeading(SECTION);
    index = body.getNumChildren();
  }
  entries.forEach(function (entry) { index = insertEntry_(body, index, entry); });
}

/** Index just past the last element of the section headed `category`, or -1 if absent. */
function sectionEnd_(body, category) {
  const n = body.getNumChildren();
  for (let i = 0; i < n; i++) {
    if (!isHeading_(body.getChild(i))) continue;
    if (body.getChild(i).asParagraph().getText().trim() !== category) continue;
    let j = i + 1;
    while (j < n && !isHeading_(body.getChild(j))) j++;
    // Skip back over trailing empty paragraphs so new bullets join the existing list.
    while (j > i + 1 && isEmptyParagraph_(body.getChild(j - 1))) j--;
    return j;
  }
  return -1;
}

function isHeading_(el) {
  if (el.getType() !== DocumentApp.ElementType.PARAGRAPH) return false;
  const h = el.asParagraph().getHeading();
  return h !== DocumentApp.ParagraphHeading.NORMAL && h !== DocumentApp.ParagraphHeading.SUBTITLE;
}

function isEmptyParagraph_(el) {
  return el.getType() === DocumentApp.ElementType.PARAGRAPH && el.asParagraph().getText().trim() === '';
}

function insertEntry_(body, index, entry) {
  const term = String(entry.term || '').toUpperCase();
  const added = entry.dateAdded ? new Date(entry.dateAdded) : new Date();
  const date = Utilities.formatDate(added, Session.getScriptTimeZone(), 'd MMM yyyy');
  const trivia = String(entry.trivia || '').replace(/\s+/g, ' ').trim();

  const main = term + (trivia ? ' — ' + trivia : '') + '  (' + date + ')';
  const item = listItem_(body, index++, main, 0);
  const text = item.editAsText();
  text.setBold(0, term.length - 1, true);
  text.setForegroundColor(main.length - date.length - 2, main.length - 1, '#5B6170');

  if (entry.etymology) index = detail_(body, index, 'Origin: ', entry.etymology, false);
  if (entry.usage) index = detail_(body, index, 'Usage: ', '“' + entry.usage + '”', true);
  return index;
}

function detail_(body, index, label, value, italic) {
  const item = listItem_(body, index, label + value, 1);
  const text = item.editAsText();
  text.setBold(0, label.length - 1, true);
  if (italic) text.setItalic(label.length, label.length + value.length - 1, true);
  return index + 1;
}

function listItem_(body, index, content, level) {
  const item = index >= body.getNumChildren()
    ? body.appendListItem(content)
    : body.insertListItem(index, content);
  item.setHeading(DocumentApp.ParagraphHeading.NORMAL);
  item.setNestingLevel(level);
  item.setGlyphType(level === 0 ? DocumentApp.GlyphType.BULLET : DocumentApp.GlyphType.HOLLOW_BULLET);
  item.editAsText().setBold(false).setItalic(false).setForegroundColor('#1A1C22');
  return item;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
