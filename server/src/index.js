import { createApp } from './app.js';
import { config } from './config.js';
import { migrate, closePool, getPool } from './db/index.js';
import { seed } from './db/seed.js';
import { bootstrapFirstAdmin } from './db/bootstrap.js';

async function main() {
  await getPool();
  await migrate();

  if (config.useMemoryDb) {
    // The in-memory database starts empty every boot, so it is seeded with
    // full demo data automatically — otherwise there would be no account to
    // sign in with.
    await seed({ quiet: true });
    console.log('[boot] Demo data loaded. Sign in as ADM001 / ' + config.seedAdminPassword);
  } else {
    // A real, empty database gets exactly one admin account, not demo data —
    // a fresh production deployment should hold a real roster, not fake
    // Sewadars. No-ops once the database has anyone in it.
    const created = await bootstrapFirstAdmin();
    if (created) {
      console.log(`[boot] No Sewadars found — created the first administrator.`);
      console.log(`[boot]   Sign in as: ${created.id} / ${created.password}`);
      console.log(`[boot]   Change this password immediately from the console.`);
    }
  }

  const app = createApp();
  const server = app.listen(config.port, () => {
    console.log(`[boot] Sewa Entry API listening on http://localhost:${config.port} (${config.env})`);
  });

  const shutdown = async (signal) => {
    console.log(`\n[boot] ${signal} received, shutting down.`);
    server.close(async () => {
      await closePool();
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 10_000).unref();
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((err) => {
  console.error('[boot] Failed to start:', err);
  process.exit(1);
});
