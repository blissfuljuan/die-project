/**
 * CSV for downloads (for example the one-time temporary credentials after a roster import). A cell that starts with = + - @
 * (or a tab or carriage return) is read as a formula by spreadsheet programs, and the data comes from uploaded files, so
 * such cells get a leading apostrophe. Cells with commas, quotes or line breaks are quoted.
 */
export function csvCell(value: string | number | null | undefined): string {
  let text = value === null || value === undefined ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

export function toCsv(header: readonly string[], rows: ReadonlyArray<ReadonlyArray<string | number | null | undefined>>): string {
  return [header, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}
