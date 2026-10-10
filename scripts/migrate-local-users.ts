import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { Prisma } from '@prisma/client';
import { prisma } from '../lib/prisma';
import type { Role } from '../lib/user-types';

const require = createRequire(import.meta.url);
const { loadEnvConfig } = require('@next/env') as typeof import('@next/env');
loadEnvConfig(process.cwd());

type LocalUser = {
  email: string;
  fullName: string;
  passwordHash: string;
  role: Role;
  username: string;
};

const validRoles = ['STUDENT', 'TEACHER', 'ADMIN'] as const;
const dryRun = process.argv.includes('--dry-run');

function parseUsers(value: unknown): LocalUser[] {
  if (!Array.isArray(value)) {
    throw new Error('Tệp tài khoản local phải chứa một mảng JSON.');
  }

  const seenEmails = new Set<string>();
  return value.map((entry, index) => {
    if (!entry || typeof entry !== 'object') {
      throw new Error(`Bản ghi tài khoản thứ ${index + 1} không hợp lệ.`);
    }

    const user = entry as Record<string, unknown>;
    if (
      typeof user.email !== 'string' ||
      typeof user.fullName !== 'string' ||
      typeof user.passwordHash !== 'string' ||
      typeof user.username !== 'string' ||
      typeof user.role !== 'string' ||
      !validRoles.includes(user.role as (typeof validRoles)[number])
    ) {
      throw new Error(`Bản ghi tài khoản thứ ${index + 1} thiếu trường hợp lệ.`);
    }

    const email = user.email.trim().toLowerCase();
    if (!email || !user.fullName.trim() || !user.passwordHash || !user.username.trim()) {
      throw new Error(`Bản ghi tài khoản thứ ${index + 1} chứa giá trị rỗng.`);
    }
    if (seenEmails.has(email)) {
      throw new Error('Tệp tài khoản local có email trùng lặp; đã dừng trước khi ghi dữ liệu.');
    }
    seenEmails.add(email);

    return {
      email,
      fullName: user.fullName.trim(),
      passwordHash: user.passwordHash,
      role: user.role as Role,
      username: user.username.trim(),
    };
  });
}

function usernameBase(username: string): string {
  return username.replace(/[^a-zA-Z0-9_.-]/g, '').slice(0, 80) || 'student';
}

async function uniqueUsername(
  transaction: Prisma.TransactionClient,
  user: LocalUser,
): Promise<string> {
  const base = usernameBase(user.username);
  const hash = createHash('sha256').update(user.email).digest('hex');

  for (let length = 12; length <= hash.length; length += 4) {
    const candidate = `${base}-${hash.slice(0, length)}`;
    const existing = await transaction.user.findUnique({
      where: { username: candidate },
      select: { userId: true },
    });
    if (!existing) return candidate;
  }

  throw new Error('Không thể tạo username duy nhất cho một tài khoản local.');
}

async function main() {
  const sourcePath = path.join(process.cwd(), '.local', 'registered-users.json');
  const source = await readFile(sourcePath, 'utf8');
  const users = parseUsers(JSON.parse(source) as unknown);

  const result = await prisma.$transaction(
    async (transaction) => {
      const roles = await transaction.role.findMany({
        where: { roleCode: { in: [...validRoles] } },
        select: { roleId: true, roleCode: true },
      });
      const roleIds = new Map(roles.map((role) => [role.roleCode, role.roleId]));
      const missingRoles = [...new Set(users.map((user) => user.role))]
        .filter((role) => !roleIds.has(role));
      if (missingRoles.length > 0) {
        throw new Error(`Database chưa có role cần thiết: ${missingRoles.join(', ')}.`);
      }

      let imported = 0;
      let skipped = 0;

      for (const user of users) {
        const existing = await transaction.user.findUnique({
          where: { email: user.email },
          select: { userId: true },
        });
        if (existing) {
          skipped += 1;
          continue;
        }

        if (!dryRun) {
          await transaction.user.create({
            data: {
              username: await uniqueUsername(transaction, user),
              email: user.email,
              passwordHash: user.passwordHash,
              fullName: user.fullName,
              userRoles: {
                create: {
                  role: { connect: { roleId: roleIds.get(user.role)! } },
                },
              },
            },
          });
        }
        imported += 1;
      }

      return { imported, skipped, total: users.length };
    },
    { maxWait: 10_000, timeout: 60_000 },
  );

  console.log(
    `${dryRun ? 'Xem trước' : 'Hoàn tất'}: ${result.imported} sẽ được chuyển, ` +
      `${result.skipped} đã có trong database, ${result.total} tổng cộng.`,
  );
  if (dryRun) {
    console.log('Chế độ xem trước không ghi thay đổi vào PostgreSQL.');
  } else {
    console.log('Tệp local được giữ nguyên làm bản sao.');
  }
}

main()
  .catch((error: unknown) => {
    console.error('Không thể chuyển tài khoản local sang PostgreSQL.', error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
