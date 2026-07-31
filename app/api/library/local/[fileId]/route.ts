import { readFile } from 'node:fs/promises';
import { getLocalBook } from '@/lib/localLibrary';

export async function GET(
  request: Request,
  context: { params: Promise<{ fileId: string }> }
) {
  const { fileId } = await context.params;
  const decoded = decodeURIComponent(fileId);
  const book = await getLocalBook(decoded);

  if (!book) {
    return Response.json({ error: 'Local book not found' }, { status: 404 });
  }

  const url = new URL(request.url);
  if (url.searchParams.get('metadata') === '1') {
    return Response.json(book.metadata);
  }

  const buffer = await readFile(book.path);
  return new Response(buffer, {
    headers: {
      'Content-Type': book.metadata.mimeType,
      'Cache-Control': 'no-store',
    },
  });
}
