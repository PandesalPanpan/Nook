import type { Recurrence, Task } from './index';

export function validDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
}
const days = (date: string) => Date.parse(date) / 86400000;
const date = (day: number) => new Date(day * 86400000).toISOString().slice(0, 10);

/** Calendar cadence anchored to the original date, including month-end clamping. */
export function nextOccurrence(rule: Recurrence, after: string): string {
  if (!validDate(after) || !validDate(rule.anchorDate)) throw new Error('Invalid recurrence date');
  if (rule.frequency !== 'monthly') {
    const step = rule.interval * (rule.frequency === 'weekly' ? 7 : 1);
    return date(days(rule.anchorDate) + Math.max(1, Math.floor((days(after) - days(rule.anchorDate)) / step) + 1) * step);
  }
  const [year, month, day] = rule.anchorDate.split('-').map(Number);
  const [afterYear, afterMonth] = after.split('-').map(Number);
  let count = Math.max(1, Math.floor(((afterYear - year) * 12 + afterMonth - month) / rule.interval));
  for (;;) {
    const target = new Date(Date.UTC(year, month - 1 + count * rule.interval, 1));
    const lastDay = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
    target.setUTCDate(Math.min(day, lastDay));
    const result = target.toISOString().slice(0, 10);
    if (result > after) return result;
    count++;
  }
}
export function recurringTask(task: Task, rule: Recurrence): Task {
  const previous = task.doDate ?? task.deadline ?? rule.anchorDate;
  const next = nextOccurrence(rule, previous);
  const shift = days(next) - days(previous);
  const {reminderId: _reminder, ...rest} = task;
  void _reminder;
  return {...rest, completed: false, doDate: task.doDate ? date(days(task.doDate) + shift) : task.deadline ? undefined : next,
    deadline: task.deadline ? date(days(task.deadline) + shift) : undefined};
}
export async function occurrenceId(recurrenceId: string, date: string): Promise<string> {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`nook:recurrence:${recurrenceId}:${date}`));
  return 'repeat-' + [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');
}
