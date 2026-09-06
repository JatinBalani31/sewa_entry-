import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import morgan from 'morgan';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';
import { errorHandler, notFoundHandler } from './middleware/error.js';
import { authRouter } from './routes/auth.js';
import { sewadarsRouter } from './routes/sewadars.js';
import { departmentsRouter } from './routes/departments.js';
import { attendanceRouter } from './routes/attendance.js';
import { kioskRouter } from './routes/kiosk.js';
import { reportsRouter } from './routes/reports.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const WEB_DIST = path.resolve(here, '../../web/dist');

export function createApp() {
  const app = express();

  // Rate limiters key on the client IP; behind nginx or a container proxy that
  // only works once Express is told to trust the forwarding header.
  app.set('trust proxy', config.isProd ? 1 : false);
  app.disable('x-powered-by');

  app.use(helmet({
    // The bundled console is same-origin; relaxing this lets the map tiles and
    // inline styles the SPA ships with load.
    contentSecurityPolicy: false,
    crossOriginEmbedderPolicy: false,
  }));
  app.use(cors({ origin: config.security.corsOrigins, credentials: true }));
  // Signature images arrive as base64 data URLs, well past the 100 KB default.
  app.use(express.json({ limit: '1mb' }));
  app.use(morgan(config.isProd ? 'combined' : 'dev'));

  app.get('/api/health', (_req, res) => {
    res.json({
      ok: true,
      service: 'sewa-entry-api',
      env: config.env,
      database: config.useMemoryDb ? 'in-memory (development only)' : 'postgresql',
      serverTime: new Date().toISOString(),
    });
  });

  // Clients read the server clock from here rather than trusting the device's
  // own, which is the whole point of server-side time enforcement.
  app.get('/api/time', (_req, res) => {
    res.json({ serverTime: new Date().toISOString(), timezone: config.report.timezone });
  });

  app.use('/api/auth', authRouter);
  app.use('/api/sewadars', sewadarsRouter);
  app.use('/api/departments', departmentsRouter);
  app.use('/api/attendance', attendanceRouter);
  app.use('/api/kiosk', kioskRouter);
  app.use('/api/reports', reportsRouter);

  // In production the API also serves the built console; in development Vite
  // does that on :5173 and proxies /api here.
  if (fs.existsSync(WEB_DIST)) {
    app.use(express.static(WEB_DIST));
    app.get(/^(?!\/api\/).*/, (_req, res) => res.sendFile(path.join(WEB_DIST, 'index.html')));
  }

  app.use('/api', notFoundHandler);
  app.use(errorHandler);

  return app;
}
