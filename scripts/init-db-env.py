#!/usr/bin/env python3
"""Prepare local passwords and MySQL initialization; do not overwrite credentials."""
from pathlib import Path
import secrets
import re

root = Path(__file__).resolve().parents[1]
path = root / 'docker' / '.env'
if not path.exists():
    with path.open('x', encoding='utf-8') as file:
        for key in ('MYSQL_ROOT_PASSWORD', 'DB_PASSWORD', 'DB_TEST_PASSWORD'):
            file.write(f'{key}={secrets.token_hex(24)}\n')
    path.chmod(0o600)
values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))
password = values['DB_TEST_PASSWORD']
if not re.fullmatch(r'[a-f0-9]{48}', password):
    raise SystemExit('Expected generated hexadecimal test password.')
init = root / 'docker/mysql/init'
init.mkdir(parents=True, exist_ok=True)
sql = init / '01-test-database.sql'
sql.write_text(f"CREATE DATABASE IF NOT EXISTS ai_knowledge_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;\n"
               f"CREATE USER IF NOT EXISTS 'ai_knowledge_test'@'%' IDENTIFIED BY '{password}';\n"
               "GRANT ALL ON ai_knowledge_test.* TO 'ai_knowledge_test'@'%';\n")
sql.chmod(0o644)  # Container mysql user must be able to read the initialization SQL.
print('Local database configuration ready; existing passwords preserved.')
