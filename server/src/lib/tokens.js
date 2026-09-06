import jwt from 'jsonwebtoken';
import { config } from '../config.js';

export const TOKEN_TYPE = { USER: 'user', KIOSK: 'kiosk' };

export function signUserToken(sewadar) {
  return jwt.sign(
    { sub: sewadar.id, role: sewadar.role, name: sewadar.full_name, typ: TOKEN_TYPE.USER },
    config.jwt.secret,
    { expiresIn: config.jwt.expiresIn },
  );
}

/** A kiosk token authorises a *station*, not a person. */
export function signKioskToken({ stationName, departmentId, departmentName, issuedBy }) {
  return jwt.sign(
    {
      sub: `kiosk:${stationName}`,
      stationName,
      departmentId: departmentId ?? null,
      departmentName: departmentName ?? null,
      issuedBy,
      typ: TOKEN_TYPE.KIOSK,
    },
    config.jwt.secret,
    { expiresIn: config.jwt.kioskExpiresIn },
  );
}

export function verifyToken(token) {
  return jwt.verify(token, config.jwt.secret);
}
