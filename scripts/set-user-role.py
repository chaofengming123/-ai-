#!/usr/bin/env python3
"""Local Docker development database only. Preview by default; --apply changes the named account."""
import argparse
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description="预览或设置本地课程账号角色，不修改密码。")
parser.add_argument("--username", required=True, help="已注册用户名")
parser.add_argument("--role", choices=["USER", "EDITOR", "ADMIN"], nargs="+", required=True)
parser.add_argument("--apply", action="store_true", help="实际应用角色变更；省略则仅预览")
args = parser.parse_args()
username = args.username.strip().lower()
if not re.fullmatch(r"[a-z0-9_]{3,32}", username):
    parser.error("用户名格式不正确")
root = Path(__file__).resolve().parents[1]
command = [
    "docker", "compose", "--env-file", str(root / "docker/.env"),
    "-f", str(root / "docker/compose.yml"), "exec", "-T", "mysql",
    "sh", "-c",
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=ai_knowledge --batch --skip-column-names',
]
def query(sql):
    result = subprocess.run(command, input=sql, text=True, capture_output=True)
    if result.returncode:
        detail = result.stderr.strip().splitlines()
        reason = detail[-1] if detail else "没有返回具体原因"
        raise SystemExit(
            "数据库操作失败。请确认 Docker Desktop 和本项目 MySQL 已启动。\n"
            f"原始原因：{reason}"
        )
    return result.stdout.strip()

roles = sorted(set(args.role))
role_values = ",".join(
    f"((SELECT id FROM app_user WHERE username='{username}'),(SELECT id FROM app_role WHERE code='{role}'))"
    for role in roles
)
current_row = query(f"SELECT u.id,GROUP_CONCAT(r.code ORDER BY r.code) FROM app_user u LEFT JOIN app_user_role ur ON ur.user_id=u.id LEFT JOIN app_role r ON r.id=ur.role_id WHERE u.username='{username}' GROUP BY u.id;")
if not current_row:
    raise SystemExit("未找到该用户名，请先注册；未做任何修改。")
parts = current_row.split("\t", 1)
current = parts[1] if len(parts) == 2 and parts[1] != "NULL" else "未分配角色"
print(f"账号 {username}：{current} → {','.join(roles)}（替换全部角色）")
if not args.apply:
    print("仅预览。如确认要修改该账号，在同一命令后添加 --apply。")
else:
    # 用户名已限制为 ASCII 字母、数字和下划线，角色由固定 choices 限定。
    result = query(f"START TRANSACTION; SELECT id FROM app_user WHERE username = '{username}' FOR UPDATE;"
                   f"DELETE ur FROM app_user_role ur JOIN app_user u ON u.id=ur.user_id WHERE u.username='{username}';"
                   f"INSERT INTO app_user_role(user_id,role_id) VALUES {role_values};"
                   f"SELECT CONCAT('role=',r.code) FROM app_user_role ur JOIN app_user u ON u.id=ur.user_id JOIN app_role r ON r.id=ur.role_id WHERE u.username='{username}'; COMMIT;")
    if {line[5:] for line in result.splitlines() if line.startswith('role=')} != set(roles):
        raise SystemExit("角色结果不完整，请检查账号及角色配置。")
    print("角色已更新。后端下一次请求按新角色授权；前端请核对登录状态或重新登录。")
