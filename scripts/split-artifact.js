const fs = require('fs');
const path = require('path');

const source = path.resolve(process.argv[2]);
const output = path.resolve(process.argv[3]);
const prefix = process.argv[4];
const chunkSize = 20 * 1024 * 1024;
if (!source || !output || !prefix || !fs.statSync(source).isFile()) throw new Error('Invalid split-artifact arguments.');
fs.mkdirSync(output, { recursive: true });
const descriptor = fs.openSync(source, 'r');
const buffer = Buffer.alloc(chunkSize);
let index = 0;
let offset = 0;
try {
  while (true) {
    const size = fs.readSync(descriptor, buffer, 0, chunkSize, offset);
    if (!size) break;
    fs.writeFileSync(path.join(output, `${prefix}.part${String(index).padStart(2, '0')}`), buffer.subarray(0, size));
    offset += size;
    index++;
  }
} finally {
  fs.closeSync(descriptor);
}
process.stdout.write(JSON.stringify({ source, bytes: offset, parts: index }));
