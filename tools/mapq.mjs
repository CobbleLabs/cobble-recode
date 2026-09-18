/*
 * mapq - interactive query tool for the Yarn tiny v2 mapping file.
 *
 *   node tools/mapq.mjs class  <substring of named class>
 *   node tools/mapq.mjs member <named class> [substring of member name]
 *   node tools/mapq.mjs find   <named member name>
 */

import { readFileSync } from 'node:fs';
import { parseTiny } from './tiny.mjs';

const MAPPINGS = 'build/mappings/mappings.tiny';

const [, , command, ...args] = process.argv;
const tiny = parseTiny(readFileSync(MAPPINGS, 'utf8'));

function classesMatching(needle) {
  const lower = needle.toLowerCase();
  return [...tiny.classes.values()].filter((entry) => entry.named.toLowerCase().includes(lower));
}

function printClass(entry) {
  console.log(`${entry.named}\n  -> ${entry.intermediary}`);
}

switch (command) {
  case 'class': {
    const matches = classesMatching(args[0] ?? '');
    for (const entry of matches.slice(0, 60)) printClass(entry);
    console.log(`(${matches.length} matches)`);
    break;
  }
  case 'member': {
    const matches = classesMatching(args[0] ?? '');
    const filter = (args[1] ?? '').toLowerCase();
    for (const entry of matches.slice(0, 6)) {
      printClass(entry);
      for (const field of entry.fields) {
        if (filter && !field.named.toLowerCase().includes(filter)) continue;
        console.log(`    F ${field.named} ${field.desc}  -> ${field.intermediary}`);
      }
      for (const method of entry.methods) {
        if (filter && !method.named.toLowerCase().includes(filter)) continue;
        console.log(`    M ${method.named}${method.desc}  -> ${method.intermediary}`);
      }
    }
    break;
  }
  case 'find': {
    const needle = (args[0] ?? '').toLowerCase();
    let printed = 0;
    outer: for (const entry of tiny.classes.values()) {
      for (const method of entry.methods) {
        if (method.named.toLowerCase() !== needle) continue;
        console.log(`${entry.named}.${method.named}${method.desc}  -> ${entry.intermediary}.${method.intermediary}`);
        if (++printed >= 80) break outer;
      }
      for (const field of entry.fields) {
        if (field.named.toLowerCase() !== needle) continue;
        console.log(`${entry.named}#${field.named} ${field.desc}  -> ${entry.intermediary}.${field.intermediary}`);
        if (++printed >= 80) break outer;
      }
    }
    break;
  }
  default:
    console.log('usage: node tools/mapq.mjs class|member|find <args>');
}
