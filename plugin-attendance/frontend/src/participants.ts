/**
 * Подпись участника по его identity: имён в событиях streamapi нет.
 *
 * Meet собирает identity гостя как «<имя>-<15 символов [0-9a-z]>»: имя в нижнем регистре, пробелы
 * и дефисы заменены на «_», всё кроме латиницы и цифр выброшено. Имя без латиницы, например
 * кириллическое, превращается в «participant». У участников с учётной записью identity —
 * идентификатор пользователя, его показываем как есть.
 */
const GUEST_IDENTITY = /^([a-z0-9_]+)-([0-9a-z]{15})$/;

export interface ParticipantLabel {
  name: string;
  /** Начало случайной части identity гостя: различает одинаковые имена. */
  tag: string | null;
  /** Имя ничего не говорит об участнике («Участник»), метку показываем всегда. */
  generic: boolean;
  initials: string;
  hue: number;
}

export function labelFor(participantId: string): ParticipantLabel {
  const hue = hueOf(participantId);
  const match = GUEST_IDENTITY.exec(participantId);
  if (!match) {
    return { name: participantId, tag: null, generic: false, initials: initialsOf(participantId), hue };
  }
  const [, prefix, suffix] = match;
  const tag = suffix.slice(0, 4);
  if (prefix === 'participant' || !prefix.replaceAll('_', '')) {
    return { name: 'Участник', tag, generic: true, initials: 'У', hue };
  }
  const name = prefix
    .split('_')
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
  return { name, tag, generic: false, initials: initialsOf(name), hue };
}

/** Подписи для всего списка: одинаковые имена получают минимальную различающую метку. */
export function labelsFor(participantIds: readonly string[]): Map<string, ParticipantLabel & { showTag: boolean }> {
  const ids = [...new Set(participantIds)];
  const names = new Map(ids.map(id => [id, labelFor(id)]));
  const groups = new Map<string, string[]>();
  for (const id of ids) {
    const name = names.get(id)!.name;
    const group = groups.get(name) ?? [];
    group.push(id);
    groups.set(name, group);
  }

  const labels = new Map<string, ParticipantLabel & { showTag: boolean }>();
  for (const id of ids) {
    const label = names.get(id)!;
    const group = groups.get(label.name)!;
    if (label.tag === null || (!label.generic && group.length === 1)) {
      labels.set(id, { ...label, showTag: false });
      continue;
    }
    const suffix = GUEST_IDENTITY.exec(id)![2];
    let tag: string | null = null;
    for (let length = 4; length <= suffix.length; length++) {
      const prefix = suffix.slice(0, length);
      if (!group.some(other => other !== id && GUEST_IDENTITY.exec(other)?.[2].startsWith(prefix))) {
        tag = prefix;
        break;
      }
    }
    labels.set(id, tag === null ? rawLabel(id, label.hue) : { ...label, tag, showTag: true });
  }

  // Сам identity может совпасть с оформленным именем гостя. В этом случае сохраняем исходные ID.
  let changed: boolean;
  do {
    changed = false;
    const used = new Map<string, string[]>();
    for (const [id, label] of labels) {
      const text = label.name + (label.showTag ? ` #${label.tag}` : '');
      const group = used.get(text) ?? [];
      group.push(id);
      used.set(text, group);
    }
    for (const group of used.values()) {
      if (group.length < 2) continue;
      for (const id of group) {
        const label = labels.get(id)!;
        if (label.name !== id || label.showTag) {
          labels.set(id, rawLabel(id, label.hue));
          changed = true;
        }
      }
    }
  } while (changed);
  return labels;
}

function rawLabel(identity: string, hue: number): ParticipantLabel & { showTag: boolean } {
  return { name: identity, tag: null, generic: false, initials: initialsOf(identity), hue, showTag: false };
}

function initialsOf(name: string): string {
  const letters = name
    .split(/[\s_.@-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0])
    .join('');
  return letters.toUpperCase() || '?';
}

/** FNV-1a: похожие identity получают разные цвета. */
function hueOf(value: string): number {
  let hash = 0x811c9dc5;
  for (const char of value) {
    hash ^= char.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0) % 360;
}
