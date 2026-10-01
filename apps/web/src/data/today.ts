import type {Entity} from '../../../../packages/schemas/src/index';

export function upcomingTasks(tasks: Entity<'task'>[], today: string): Entity<'task'>[] {
  return tasks.filter(task=>!task.deleted && !task.archived && !task.data.completed && !!task.data.deadline && task.data.deadline>today);
}
