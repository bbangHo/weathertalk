// 기기 로컬 저장소 래퍼 (07 §6.1). 선언된 키만 읽고 쓴다.
import AsyncStorage from '@react-native-async-storage/async-storage';

import {
  GENERAL_KEYS,
  type GeneralKey,
  type GeneralSchema,
  SECURE_KEYS,
  type SecureKey,
  type SecureSchema,
  STORAGE_KEY_PREFIX,
} from './schema';
import * as SecureStore from './secureBackend';

export class UndeclaredStorageKeyError extends Error {
  constructor(key: string) {
    super(`07 §6.1에 없는 저장 키입니다: ${key}`);
    this.name = 'UndeclaredStorageKeyError';
  }
}

export class CoordinateStorageError extends Error {
  constructor(key: string) {
    super(`좌표는 기기에 저장하지 않습니다(ADR 0009): ${key}`);
    this.name = 'CoordinateStorageError';
  }
}

const COORDINATE_FIELDS = new Set(['latitude', 'longitude', 'lat', 'lng', 'lon', 'coords', 'coordinate', 'coordinates']);

// 마지막 홈 응답처럼 서버 응답을 통째로 넣는 항목에 좌표가 섞여 들어가는 것을 막는다.
function containsCoordinates(value: unknown, depth = 0): boolean {
  if (depth > 20 || value === null || typeof value !== 'object') {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some((item) => containsCoordinates(item, depth + 1));
  }
  return Object.entries(value).some(
    ([field, nested]) => COORDINATE_FIELDS.has(field.toLowerCase()) || containsCoordinates(nested, depth + 1),
  );
}

function assertDeclared(key: string, declared: readonly string[]): void {
  if (!declared.includes(key)) {
    throw new UndeclaredStorageKeyError(key);
  }
}

function storageKey(key: string): string {
  return STORAGE_KEY_PREFIX + key;
}

function parse<T>(raw: string | null): T | null {
  if (raw === null) {
    return null;
  }
  try {
    return JSON.parse(raw) as T;
  } catch {
    // 깨진 값은 없는 것으로 본다.
    return null;
  }
}

export const secureStore = {
  async get<K extends SecureKey>(key: K): Promise<SecureSchema[K] | null> {
    assertDeclared(key, SECURE_KEYS);
    return parse<SecureSchema[K]>(await SecureStore.getItemAsync(storageKey(key)));
  },
  async set<K extends SecureKey>(key: K, value: SecureSchema[K]): Promise<void> {
    assertDeclared(key, SECURE_KEYS);
    await SecureStore.setItemAsync(storageKey(key), JSON.stringify(value));
  },
  async remove(key: SecureKey): Promise<void> {
    assertDeclared(key, SECURE_KEYS);
    await SecureStore.deleteItemAsync(storageKey(key));
  },
};

export const generalStore = {
  async get<K extends GeneralKey>(key: K): Promise<GeneralSchema[K] | null> {
    assertDeclared(key, GENERAL_KEYS);
    return parse<GeneralSchema[K]>(await AsyncStorage.getItem(storageKey(key)));
  },
  async set<K extends GeneralKey>(key: K, value: GeneralSchema[K]): Promise<void> {
    assertDeclared(key, GENERAL_KEYS);
    if (containsCoordinates(value)) {
      throw new CoordinateStorageError(key);
    }
    await AsyncStorage.setItem(storageKey(key), JSON.stringify(value));
  },
  async remove(...keys: GeneralKey[]): Promise<void> {
    keys.forEach((key) => assertDeclared(key, GENERAL_KEYS));
    if (keys.length > 0) {
      await AsyncStorage.multiRemove(keys.map(storageKey));
    }
  },
};
