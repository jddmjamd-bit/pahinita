@echo off
npx.cmd repomix --include "src/main/**/*.java,src/main/**/*.sql,frontend/src/**/*.js,frontend/src/**/*.jsx,agent/**,pom.xml,ARCHITECTURE.md" --no-security-check --output codigo-puro.txt