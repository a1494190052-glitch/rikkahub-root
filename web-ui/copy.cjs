const { copyFileSync, mkdirSync, readdirSync, rmSync, statSync } = require("fs");
const { join, dirname } = require("path");

const SOURCE_DIR = "./build/client";
const TARGET_DIR = "../web/src/main/resources/static";

function copyDirectory(src, dest) {
  mkdirSync(dest, { recursive: true });
  const entries = readdirSync(src, { withFileTypes: true });
  for (const entry of entries) {
    const srcPath = join(src, entry.name);
    const destPath = join(dest, entry.name);
    if (entry.isDirectory()) {
      copyDirectory(srcPath, destPath);
    } else {
      mkdirSync(dirname(destPath), { recursive: true });
      copyFileSync(srcPath, destPath);
    }
  }
}

try {
  console.log("Copying build output...");
  statSync(SOURCE_DIR);
  try { rmSync(TARGET_DIR, { recursive: true, force: true }); } catch(e) {}
  copyDirectory(SOURCE_DIR, TARGET_DIR);
  console.log("Done!");
} catch (error) {
  console.error("Failed:", error);
  process.exit(1);
}
