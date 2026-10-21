// Human titles remain separate from the stable file name used by exports.
export const videoSlug = (title: string) => title.normalize("NFKD").replace(/\p{M}/gu, "").trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^[._-]+|[._-]+$/g, "");

export function copyTitle(title: string, names: string[]) {
  let candidate = `${title} copy`, i = 2;
  while (names.includes(videoSlug(candidate))) candidate = `${title} copy ${i++}`;
  return candidate;
}

export const searchText = (text: string) => text.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase();
