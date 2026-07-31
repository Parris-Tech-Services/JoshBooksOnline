import { getServerSession } from 'next-auth';
import { updateBookProgress } from '@/lib/googleDrive';
import { decodeLocalId, updateLocalBookProgress } from '@/lib/localLibrary';
import authOptions from '@/lib/auth';

export async function POST(request: Request) {
  const session = await getServerSession(authOptions);

  try {
    const { fileId, progress, location } = await request.json();

    if (!fileId || progress === undefined || !location) {
      return Response.json(
        { error: 'Missing required fields: fileId, progress, location' },
        { status: 400 }
      );
    }

    if (decodeLocalId(fileId)) {
      const updated = await updateLocalBookProgress(fileId, progress, location);
      return updated
        ? new Response(null, { status: 204 })
        : Response.json({ error: 'Local book not found' }, { status: 404 });
    }

    if (!session?.accessToken) {
      return Response.json({ error: 'Unauthorized' }, { status: 401 });
    }

    await updateBookProgress(session.accessToken, fileId, progress, location);
    return new Response(null, { status: 204 });
  } catch (error) {
    console.error('Failed to update progress:', error);
    return Response.json(
      { error: 'Failed to update progress' },
      { status: 500 }
    );
  }
}
