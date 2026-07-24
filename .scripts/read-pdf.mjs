import fs from 'fs';
import * as pdfjsLib from 'pdfjs-dist';

const pdfPath = 'D:/word/iot-platform-demo/.trae/documents/建筑设备与系统调适 .pdf';
const data = new Uint8Array(fs.readFileSync(pdfPath));

const doc = await pdfjsLib.getDocument({ data, verbosity: 0 }).promise;
console.log('=== 总页数:', doc.numPages);

for (let i = 1; i <= Math.min(doc.numPages, 100); i++) {
  const page = await doc.getPage(i);
  const content = await page.getTextContent();
  const text = content.items.map(it => it.str).join('');
  console.log(`\n=== 第 ${i} 页 ===`);
  console.log(text);
}
