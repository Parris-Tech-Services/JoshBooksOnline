import { audioMime, exportName, prepareRuizuFiles, MAX_SHARE_BYTES } from '@/lib/ruizuExport';
const track = { id: 'private/id', name: 'Chapter.mp3', size: 3 };
const signal = new AbortController().signal;
function mockDownload(bytes = new Uint8Array([1,2,3])) {
  const cancel = jest.fn().mockResolvedValue(undefined);
  const newRead = () => jest.fn().mockResolvedValueOnce({done:false,value:bytes}).mockResolvedValue({done:true});
  global.fetch = jest.fn().mockResolvedValue({ok:true,status:200,body:{getReader:()=>({read:newRead(),cancel})}});
  return cancel;
}
describe('RUIZU authenticated file handoff', () => {
  it('recognises supported formats only', () => {
    expect(audioMime('TRACK.MP3')).toBe('audio/mpeg'); expect(audioMime('page.html')).toBeUndefined();
  });
  it('keeps audiobook order in safe numbered names', () => {
    expect(exportName('A/B',track,9)).toBe('A_B - 0010 - Chapter.mp3');
  });
  it('fetches authenticated same-origin media and shares actual files', async () => {
    mockDownload(); const progress=jest.fn();
    const files=await prepareRuizuFiles('Book',[track, {...track,id:'other'}],signal,progress);
    expect(fetch).toHaveBeenCalledWith('/api/stream/private%2Fid?mime=audio%2Fmpeg', expect.objectContaining({credentials:'same-origin',cache:'no-store',signal}));
    expect(files.map(f=>f.name)).toEqual(['Book - 0001 - Chapter.mp3','Book - 0002 - Chapter.mp3']);
    expect(files[0].size).toBe(3); expect(progress).toHaveBeenLastCalledWith(2);
  });
  it('rejects empty selections', async () => {
    await expect(prepareRuizuFiles('Book',[],signal,jest.fn())).rejects.toThrow('Select');
  });
  it('rejects huge known sizes before fetching', async () => {
    await expect(prepareRuizuFiles('Book',[{...track,size:MAX_SHARE_BYTES+1}],signal,jest.fn())).rejects.toThrow('200 MiB');
  });
  it('rejects partial source data', async () => {
    mockDownload(); await expect(prepareRuizuFiles('Book',[{...track,size:4}],signal,jest.fn())).rejects.toThrow('Incomplete');
  });
  it('rejects authentication failures', async () => {
    global.fetch=jest.fn().mockResolvedValue({ok:false,status:401});
    await expect(prepareRuizuFiles('Book',[track],signal,jest.fn())).rejects.toThrow('Sign in');
  });
  it('rejects partial HTTP responses', async () => {
    global.fetch=jest.fn().mockResolvedValue({ok:true,status:206});
    await expect(prepareRuizuFiles('Book',[track],signal,jest.fn())).rejects.toThrow('failed');
  });
  it('rejects non-audio selections', async () => {
    await expect(prepareRuizuFiles('Book',[{...track,name:'book.pdf'}],signal,jest.fn())).rejects.toThrow('supported audio');
  });
  it('always cancels/releases the download reader', async () => {
    const cancel=mockDownload(); await prepareRuizuFiles('Book',[track],signal,jest.fn()); expect(cancel).toHaveBeenCalled();
  });
});
