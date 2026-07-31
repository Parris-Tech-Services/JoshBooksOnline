import { getServerSession } from 'next-auth';
import { getAudiobookMeta, getAudiobookTracks } from '@/lib/googleDrive';
import authOptions from '@/lib/auth';

/**
 * GET /api/library/audiobook/[id]
 * Return one audiobook's metadata, resume position, and ordered track list.
 */
export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return Response.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const { id } = await params;
    const single = new URL(request.url).searchParams.get('single') === '1';
    const meta = await getAudiobookMeta(session.accessToken, id);
    const tracks = await getAudiobookTracks(session.accessToken, id, meta.isFolder, !single);
    return Response.json({ id, ...meta, tracks });
  } catch (error) {
    console.error('Failed to fetch audiobook:', error);
    return Response.json({ error: 'Failed to fetch audiobook' }, { status: 500 });
  }
}
