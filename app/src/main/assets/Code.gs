/**
 * Cross Companion → Google Doc webhook.
 *
 * Receives journal entries from the Cross Companion Android app and appends them,
 * formatted and timestamped, to the bottom of a Google Doc you own.
 *
 * Setup: open your Doc → Extensions → Apps Script → replace everything with this file →
 * Deploy → New deployment → Web app (Execute as: Me, Who has access: Anyone) → copy the URL
 * into the app's Journal tab.
 */

// Optional: paste a Doc ID (the long part of the Doc's URL) to write to a specific Doc.
// Leave empty when this script was opened from the Doc via Extensions → Apps Script.
const DOC_ID = '';

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    const doc = DOC_ID ? DocumentApp.openById(DOC_ID) : DocumentApp.getActiveDocument();
    if (!doc) throw new Error('No Doc found: set DOC_ID or create the script from inside a Doc.');
    const body = doc.getBody();

    if (data.type === 'export') {
      const stamp = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'd MMM yyyy, HH:mm');
      body.appendParagraph('Journal export · ' + stamp).setHeading(DocumentApp.ParagraphHeading.HEADING1);
      (data.entries || []).forEach(function (entry) { appendEntry_(body, entry); });
    } else if (data.type === 'entry' && data.entry) {
      appendEntry_(body, data.entry);
    } else {
      throw new Error('Unknown payload type: ' + data.type);
    }

    doc.saveAndClose();
    return json_({ ok: true });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

/** Lets you open the web app URL in a browser to check the deployment is live. */
function doGet() {
  return json_({ ok: true, message: 'Cross Companion webhook is running.' });
}

function appendEntry_(body, entry) {
  const added = entry.dateAdded ? new Date(entry.dateAdded) : new Date();
  const stamp = Utilities.formatDate(added, Session.getScriptTimeZone(), 'd MMM yyyy');

  body.appendParagraph(String(entry.term || '').toUpperCase())
    .setHeading(DocumentApp.ParagraphHeading.HEADING2);

  const meta = body.appendParagraph((entry.category || 'General') + ' · added ' + stamp);
  meta.setHeading(DocumentApp.ParagraphHeading.NORMAL);
  meta.editAsText().setItalic(true).setForegroundColor('#5B6170');

  if (entry.trivia) plain_(body.appendParagraph(entry.trivia));
  if (entry.etymology) labelled_(body, 'Origin: ', entry.etymology, false);
  if (entry.usage) labelled_(body, 'Usage: ', entry.usage, true);
  if (entry.source) labelled_(body, 'Source: ', entry.source, false);

  body.appendHorizontalRule();
}

function labelled_(body, label, value, italicValue) {
  const p = plain_(body.appendParagraph(label + value));
  const text = p.editAsText();
  text.setBold(0, label.length - 1, true);
  if (italicValue) text.setItalic(label.length, label.length + value.length - 1, true);
}

function plain_(p) {
  p.setHeading(DocumentApp.ParagraphHeading.NORMAL);
  p.editAsText().setItalic(false).setBold(false).setForegroundColor('#1A1C22');
  return p;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
