import { migrate, closePool } from './index.js';
import { config } from '../config.js';

migrate()
  .then(() => {
    console.log(`Schema applied to ${config.useMemoryDb ? 'in-memory database' : config.databaseUrl.replace(/:[^:@]*@/, ':****@')}`);
    return closePool();
  })
  .catch(async (err) => {
    console.error('Migration failed:', err.message);
    await closePool();
    process.exit(1);
  });
