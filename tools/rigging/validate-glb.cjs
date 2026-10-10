const fs = require('node:fs');
const path = require('node:path');
const validator = require('gltf-validator');

(async () => {
  const reports = {};
  for (const file of process.argv.slice(2)) {
    const name = path.basename(file);
    const report = await validator.validateBytes(new Uint8Array(fs.readFileSync(file)), {
      uri: name,
      maxIssues: 100,
      externalResourceFunction: async (uri) => { throw new Error(`Unexpected external resource: ${uri}`); },
    });
    reports[name] = report;
    console.log(`${name}: ${report.issues.numErrors} errors, ${report.issues.numWarnings} warnings`);
    for (const issue of report.issues.messages.filter((issue) => issue.severity === 0)) {
      console.error(JSON.stringify(issue));
    }
  }
  const directory = path.dirname(process.argv[2]);
  fs.writeFileSync(path.join(directory, 'khronos-validation.json'), JSON.stringify(reports, null, 2) + '\n');
  if (Object.values(reports).some((r) => r.issues.numErrors > 0)) process.exitCode = 1;
})().catch((error) => { console.error(error); process.exitCode = 1; });
