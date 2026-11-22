#!/usr/bin/env python3
"""Build editable, standalone SVG design proposals. Does not modify the Android app."""
from pathlib import Path
from html import escape as e
import json, textwrap, zipfile, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]; OUT=ROOT/'docs/ui-svg'; OUT.mkdir(parents=True,exist_ok=True)
P={'bg':'#F6F8FC','card':'#FFFFFF','ink':'#172A43','muted':'#5C6E84','line':'#E0E7F0','action':'#2867A7','blue':'#397FC9','pale':'#E8F3FF','green':'#237C66','mint':'#E5F5EE','red':'#B54443','rose':'#FFF0EE','amber':'#936218','sand':'#FFF4DF'}
ICONS={
'home':'M3 11 12 3l9 8M6 10v10h5v-6h3v6h4V10',
'play':'M8 5v14l11-7Z','pause':'M8 5v14M16 5v14','next':'M6 5v14l10-7ZM19 5v14','prev':'M18 5v14L8 12ZM5 5v14',
'library':'M5 4h14v17H5ZM8 8h8M8 12h8M8 16h5','settings':'M9 4h6l1 3 3 1 1 6-3 2-1 3H9l-1-3-3-1-1-6 3-2ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
'back':'M15 5 8 12l7 7','chevron':'m9 5 7 7-7 7','plus':'M12 5v14M5 12h14','check':'m5 12 4 4L19 6','file':'M6 3h8l4 4v14H6ZM14 3v5h5M9 12h6M9 16h6','cloud':'M7 18a5 5 0 0 1-1-10 6 6 0 0 1 11-1 5 5 0 0 1 0 11Z',
'spark':'m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z','search':'M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0ZM15 15l6 6','headphones':'M4 13v-1a8 8 0 0 1 16 0v1M4 12h4v8H4ZM16 12h4v8h-4Z',
'heart':'M12 20 3 11C-1 4 8 1 12 7 16 1 25 4 21 11Z','sync':'M4 9a8 8 0 0 1 14-3l2 2M20 3v5h-5M20 15a8 8 0 0 1-14 3l-2-2M4 21v-5h5','download':'M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4','more':'M5 12h1M11 12h1M17 12h1','shield':'M12 3 20 6v6c0 5-8 9-8 9s-8-4-8-9V6ZM8 12l3 3 5-6','filter':'M4 6h16M7 12h10M10 18h4','edit':'m4 17-1 4 4-1L20 7l-4-4Z','close':'m6 6 12 12M18 6 6 18','alert':'M12 3 22 21H2ZM12 9v5m0 3v1','volume':'M4 9h4l5-5v16l-5-5H4ZM17 8q6 4 0 8',
'folder':'M3 6h7l2 2h9v12H3Z','moon':'M19 16A8 8 0 0 1 9 4a8 8 0 1 0 10 12','chart':'M4 20V9M10 20V4M16 20v-7M22 20V7'}
def text(x,y,s,size=15,fill=None,weight=400,anchor='start'):
 if fill==P['blue']:fill=P['action']
 return f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill or P["ink"]}" font-weight="{weight}" text-anchor="{anchor}">{e(str(s))}</text>'
def rect(x,y,w,h,fill=None,r=16,stroke=None):
 return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill or P["card"]}"'+(f' stroke="{stroke}"' if stroke else '')+'/>'
def icon(x,y,name,size=24,color=None):
 return f'<g transform="translate({x} {y}) scale({size/24})" fill="none" stroke="{color or P["ink"]}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="{ICONS[name]}"/></g>'
def lines(x,y,items,size=14,fill=None,step=23):
 return ''.join(text(x,y+i*step,v,size,fill) for i,v in enumerate(items))
def pill(x,y,s,w=None,kind='blue'):
 w=w or max(58,len(s)*13+24);bg={'blue':P['pale'],'green':P['mint'],'red':P['rose'],'amber':P['sand'],'plain':P['bg']}[kind];fg={'blue':P['blue'],'green':P['green'],'red':P['red'],'amber':P['amber'],'plain':P['muted']}[kind]
 return rect(x,y,w,28,bg,14)+text(x+w/2,y+19,s,12,fg,500,'middle')
def button(y,s,kind='primary',x=24,w=364,ico=None):
 bg={'primary':P['action'],'secondary':P['pale'],'outline':P['card'],'danger':P['red'],'disabled':P['line']}[kind];fg=P['card'] if kind in ['primary','danger'] else P['muted'] if kind=='disabled' else P['blue']
 return rect(x,y,w,48,bg,24,P['line'] if kind=='outline' else None)+(icon(x+18,y+13,ico,22,fg) if ico else '')+text(x+w/2+(10 if ico else 0),y+30,s,15,fg,600,'middle')
def field(y,label,value,h=64,secret=False):
 return text(24,y+12,label,12,P['muted'],500)+rect(24,y+23,364,h-23,P['card'],10,P['line'])+text(38,y+49,'•••• •••• •••• 8K2P' if secret else value,15)
def section(y,title,sub=None):return text(24,y,title,18,weight=700)+(text(24,y+23,sub,12,P['muted']) if sub else '')
def card(y,title,detail,ico='file',badge=None,h=96,action=True):
 return rect(24,y,364,h,stroke=P['line'])+rect(40,y+18,40,40,P['pale'],12)+icon(49,y+27,ico,22,P['blue'])+text(94,y+34,title,16,weight=600)+text(94,y+58,detail,12,P['muted'])+(pill(94,y+68,badge,kind='green') if badge else '')+(icon(350,y+30,'chevron',18,P['muted']) if action else '')
def note(y,title,body,kind='blue',h=80):
 bg={'blue':P['pale'],'green':P['mint'],'red':P['rose'],'amber':P['sand']}[kind];fg={'blue':P['blue'],'green':P['green'],'red':P['red'],'amber':P['amber']}[kind]
 return rect(24,y,364,h,bg,14)+text(40,y+27,title,14,fg,600)+lines(40,y+49,body,12,P['ink'],20)
def row(y,title,detail='',ico=None,trail='›'):
 return (icon(36,y+14,ico,22,P['blue']) if ico else '')+text(72 if ico else 36,y+28,title,15,weight=500)+(text(72 if ico else 36,y+49,detail,12,P['muted']) if detail else '')+text(364,y+32,trail,20,P['muted'],anchor='end')+f'<path d="M36 {y+65}H376" stroke="{P["line"]}"/>'
def toggle(y,title,detail='',on=True):
 return text(36,y+24,title,15,weight=500)+(text(36,y+46,detail,12,P['muted']) if detail else '')+rect(328,y+5,48,28,P['blue'] if on else P['line'],14)+f'<circle cx="{362 if on else 342}" cy="{y+19}" r="10" fill="{P["card"]}"/>'
def radio(y,title,detail='',checked=True):
 return f'<circle cx="43" cy="{y+22}" r="10" fill="none" stroke="{P["blue"] if checked else P["muted"]}" stroke-width="2"/>'+ (f'<circle cx="43" cy="{y+22}" r="5" fill="{P["blue"]}"/>' if checked else '')+text(64,y+27,title,15,weight=500)+(text(64,y+49,detail,12,P['muted']) if detail else '')
def progress(y,f=.4):return rect(40,y,332,5,P['line'],3)+rect(40,y,332*f,5,P['blue'],3)
def metric(x,y,value,label):return text(x,y,value,28,P['ink'],700)+text(x,y+25,label,12,P['muted'])
SCREENS=[]
def save(n,slug,title,subtitle,body,tab=2,back=True,dark=False,desc='',nav=True):
 name=f'{n:02d}-{slug}'; top=text(24,25,'9:41',13,weight=600)+text(386,25,'▰  89%',11,anchor='end')
 top+=text(53 if back else 24,77,title,24,weight=700)+(icon(20,57,'back',24) if back else '')+text(24,108,subtitle,12,P['muted'])
 tabs=['首頁','播放器','教材庫','設定'];navbody=''
 if nav:
  navbody=rect(0,800,412,75,P['card'],0)+f'<path d="M0 800H412" stroke="{P["line"]}"/>'
  for i,(label,ico) in enumerate(zip(tabs,['home','headphones','library','settings'])):
   x=51+i*103
   if i==tab:navbody+=rect(x-27,807,54,30,P['pale'],16)
   navbody+=icon(x-11,811,ico,22,P['blue'] if i==tab else P['muted'])+text(x,859,label,12,P['blue'] if i==tab else P['muted'],600 if i==tab else 400,'middle')
 clipid='content-'+name
 content=rect(0,0,412,892,P['bg'],28)+top+f'<g clip-path="url(#{clipid})">'+body+'</g>'+navbody+rect(148,882,116,4,P['ink'],2)
 svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="412" height="892" viewBox="0 0 412 892" role="img" aria-labelledby="title desc"><title id="title">{e(title)}</title><desc id="desc">{e(desc or subtitle)}。介面提案，所有數據皆為設計示例。</desc><defs><clipPath id="{clipid}"><rect x="0" y="126" width="412" height="664"/></clipPath></defs><g font-family="Noto Sans CJK TC, Noto Sans CJK JP, Noto Sans CJK KR, sans-serif">{content}</g></svg>'
 if dark:
  mapping={'#F6F8FC':'#101B29','#FFFFFF':'#192A3F','#172A43':'#E8F0FB','#5C6E84':'#AFBED2','#E0E7F0':'#34465B','#397FC9':'#8BC1FA','#2867A7':'#8BC1FA','#E8F3FF':'#233E60','#E5F5EE':'#1B3B35','#237C66':'#8ED7B8','#FFF0EE':'#422C30','#B54443':'#FFB5AC'}
  for a,b in mapping.items():svg=svg.replace(a,b)
  # Filled primary button text needs a dark foreground on the light-blue button.
  svg=svg.replace('fill="#192A3F" font-weight="600"','fill="#101B29" font-weight="600"')
 (OUT/(name+'.svg')).write_text(svg);SCREENS.append(dict(id=n,slug=name,title=title,subtitle=subtitle,desc=desc));return svg
# Four main destinations: calm summary, single primary action.
home= pill(24,136,'離線可學習',112,'green')+text(24,204,'每天聽一點，',28,weight=700)+text(24,242,'語言更自然。',28,weight=700)
home+=rect(24,268,364,90)+metric(44,308,'240','教材')+metric(174,308,'68','已學會')+metric(298,308,'7 天','連續學習')
home+=section(398,'繼續上次學習')+rect(24,420,364,158)+pill(40,436,'日文旅行',92)+text(40,496,'旅遊會話 · 繁中 → 日文',18,weight=600)+text(40,525,'第 8 / 20 筆 · 離線語音已就緒',12,P['muted'])+progress(544,.4)
home+=button(592,'繼續播放',ico='play')+row(654,'今日到期複習','12 筆待複習 · 依目前語言配對',ico='sync')+text(24,755,'快速開始',14,P['muted'])+pill(110,736,'單字',66)+pill(184,736,'句子',66)+pill(258,736,'亂數',66)
save(1,'home','Language Listener','你的教材與學習進度都在這台裝置',home,0,False)
player=pill(24,136,'離線播放',104,'green')+pill(138,136,'日文旅行 · 8 / 20',168)+rect(24,184,364,257)+text(44,220,'繁體中文',12,P['muted'])+text(44,261,'車站在哪裡？',26,weight=600)+text(44,312,'日本語',12,P['muted'])+text(44,355,'駅はどこですか？',28,P['blue'],700)+text(44,394,'eki wa doko desu ka',14,P['muted'])+text(44,424,'其他語言  Where is the station?',12,P['muted'])
player+=progress(467,.42)+text(40,493,'00:06',12,P['muted'])+text(372,493,'00:15',12,P['muted'],anchor='end')+icon(67,535,'prev',30)+rect(163,510,86,86,P['blue'],43)+icon(189,536,'pause',34,P['card'])+icon(314,535,'next',30)
player+=pill(24,620,'收藏',80)+pill(114,620,'已學會',84)+pill(208,620,'稍後再學',94)+pill(312,620,'更多',76)+section(687,'這一筆記得嗎？')
for x,label,sub in [(24,'重學','10 分鐘'),(117,'困難','1 天'),(210,'普通','3 天'),(303,'容易','4 天')]:player+=rect(x,704,85,64,P['card'],14,P['line'])+text(x+42,729,label,14,weight=600,anchor='middle')+text(x+42,750,sub,11,P['muted'],anchor='middle')
save(2,'player','播放器','日文旅行  ›  場次設定',player,1,False)
library=field(136,'搜尋教材','搜尋文字、分類或來源')+pill(24,214,'來源',76)+pill(110,214,'全部教材',102,'plain')+button(262,'新增來源或教材',ico='plus')+section(348,'我的來源','先選來源，再開始學習或同步')+card(385,'日文旅行','100 筆 · 繁中 / 日文 · 本機',badge='可離線學習',h=112)+card(511,'韓文日常','80 筆 · 繁中 / 韓文 · CSV',badge='已存本機',h=112)+card(637,'商務三語','60 筆 · 原 Sheets 已連接',badge='可離線學習',h=112)
save(3,'library','教材庫','所有來源集中管理；離線也能新增與編輯',library,2,False)
settings=rect(24,136,364,80)+icon(40,156,'headphones',28,P['blue'])+text(88,165,'離線學習已就緒',17,weight=600)+text(88,190,'繁中 / 日文語音可用',12,P['green'])+rect(24,236,364,206)+row(242,'離線語音包','檢查、安裝與語音引擎','volume')+row(308,'備份與換機','完整教材與進度 ZIP','shield')+row(374,'介面語言與外觀','繁體中文 · 淺色模式','moon')
settings+=section(480,'連線服務')+rect(24,500,364,144)+row(508,'AI API 與模型','ChatGPT / Gemini / 相容 API','spark')+row(574,'同步偏好','僅 Wi-Fi · 啟動時同步','sync')+note(668,'離線功能不需要 API 金鑰',['AI 產生與雲端同步時才需要網路。'],h=76)+text(206,776,'Language Listener · 介面設計提案',11,P['muted'],anchor='middle')
save(4,'settings','設定','先準備語音，再安心離線學習',settings,3,False)
# Setup, source and local files.
b=section(159,'三步開始離線學習')+card(182,'1  準備教材','選擇 Excel / CSV、手動新增或 AI 產生',ico='file',h=92)+card(288,'2  準備語音','檢查母語及目標語的離線語音包',ico='volume',h=92)+card(394,'3  開始學習','下載完成後可關閉網路、鎖屏播放',ico='play',h=92)+button(528,'建立 CSV 或選擇 Excel',ico='file')+button(590,'用 AI 建立教材','secondary',ico='spark')+button(652,'先用示範教材試聽','outline')+text(24,740,'不使用 AI 或 Google，也可以完整離線學習。',12,P['muted'])
save(5,'offline-start','開始離線學習','第一次使用 · 本機優先',b,0)
b=pill(24,136,'本機 · 可離線',130,'green')+rect(24,182,364,108)+metric(44,222,'100','教材')+metric(174,222,'24','已學會')+metric(298,222,'8','到期')+button(310,'開始學習這個來源',ico='play')+button(370,'查看 / 編輯教材','outline',ico='library')+section(456,'檔案與雲端')+card(478,'連接本機 CSV','另存並連接；保留既有學習進度',h=82)+card(572,'Google Sheets 同步','先連接本機檔案，再預覽雲端合併',ico='cloud',h=82)+row(677,'重新命名來源','日文旅行','edit')
save(6,'source-detail','日文旅行','來源詳情 · 教材與進度獨立保存',b)
b=card(138,'用 AI 建立新來源','輸入主題，審核後儲存到本機',ico='spark',h=96)+card(248,'選擇 Excel / CSV','從手機選取教材檔案並初始化',ico='file',h=96)+card(358,'建立空白 CSV','先建立標準欄位，再慢慢加入教材',ico='plus',h=96)+card(468,'手動新增教材','不需要網路，也不必先選檔案',ico='edit',h=96)+card(578,'連接 Google Sheets','初始化可寫來源，或加入公開唯讀來源',ico='cloud',h=96)+note(700,'第一次使用建議',['先建立本機教材，再按需要連接雲端。'],h=76)
save(7,'new-source','新增來源','選一種開始方式',b)
b=field(136,'來源名稱','日文旅行')+field(218,'目的地','建立新來源                 ▾')+button(308,'選擇 Excel / CSV',ico='folder')+button(370,'建立初始 CSV','secondary',ico='plus')+note(450,'由你選擇檔案與儲存位置',['支援 .xlsx 與 UTF-8 CSV。','舊版 .xls 請先轉存 .xlsx 或 CSV。'],h=106)+section(598,'選取檔案之後')+lines(24,628,['預覽工作表與教材欄位。','確認初始化後才補欄位及缺少的 ID。','既有來源可以接回，學習進度會保留。'],14,P['muted'],28)
save(8,'local-file','連接本機檔案','不需要 Google 授權',b)
b=field(136,'已選檔案','Japanese-travel.xlsx')+field(216,'選擇工作表','旅行教材                         ▾')+rect(24,300,364,165)+text(40,330,'初始化預覽',18,weight=600)+lines(40,364,['已有教材                 100 筆','需要補上穩定 ID          12 筆','將補欄位       tags、pronunciation_note'],13,step=29)+note(487,'只更新選定的教材工作表',['其他工作表與自訂欄位會保留。','初始化需要此檔案的寫入權限。'],h=100)+button(620,'初始化並連接')+button(682,'重新選擇檔案','outline')
save(9,'file-initialize','預覽並初始化','第 2 步 / 2 · 確認後才寫入選取檔案',b)
b=field(136,'教材分類','旅遊')+field(208,'教材類型','句子                             ▾')+field(280,'繁體中文','車站在哪裡？')+field(352,'日本語','駅はどこですか？')+field(424,'難度','A1')+row(502,'新增其他語言','English / Bahasa Indonesia / 한국어','plus')+note(584,'儲存到這台裝置',['檔案來源可稍後按「同步本機檔案」寫回。'],h=80)+button(696,'儲存教材')
save(10,'lesson-edit','新增 / 編輯教材','至少輸入兩種語言 · 進度不受文字修改影響',b)
# Sheets and sync.
b=field(136,'來源名稱','商務三語')+field(216,'Google Sheets 原始網址','https://docs.google.com/…')+button(310,'連接、儲存並初始化',ico='cloud')+button(374,'加入已有教材（授權唯讀）','outline')+row(447,'加入公開唯讀來源','不需要 Google 登入','file')+note(540,'第一次會開啟 Google 授權',['必須具有這份試算表的讀寫權限。','取消授權不影響既有本機教材。'],h=104)+text(24,695,'唯讀加入要求教材欄位完整，不初始化工作表。',12,P['muted'])
save(11,'sheets-connect','連接 Google Sheets','雲端是選用功能，本機學習不受影響',b)
b=pill(24,136,'已取得 Google 授權',190,'green')+section(207,'檢查工作表：旅行教材')+rect(24,234,364,160)+lines(40,269,['目前已有     ID、繁中、英文、印尼文','需要補上     ja-JP、ko-KR','原有教材     100 筆'],14,step=40)+note(418,'確認後只補缺少的欄位',['不新增重複來源，也不改變教材 ID。','完成後重新預覽同步內容。'],h=104)+button(566,'初始化雲端缺少欄位')+button(630,'返回，不修改工作表','outline')
save(12,'sheets-initialize','確認工作表初始化','你的原本試算表 · 確認後才寫入',b)
b=rect(24,136,364,104)+icon(43,163,'file',28,P['blue'])+icon(190,166,'sync',24,P['blue'])+icon(330,163,'cloud',28,P['blue'])+text(42,214,'本機 CSV',13)+text(184,214,'App',13)+text(296,214,'原 Sheets',13)+section(279,'同步內容預覽')+rect(24,300,364,179)+lines(44,338,['本機新增                            8 筆','雲端修改                            3 筆','衝突                                0 筆','同步後共                          108 筆'],15,step=35)+note(503,'學習進度會保留',['依穩定教材 ID 合併。','缺少的列不會自動刪除本機教材。'],h=100)+button(636,'確認同步 App、檔案與 Sheets')+button(700,'取消','outline')
save(13,'sync-preview','預覽同步','確認內容後，才寫回本機與雲端',b)
b=pill(24,136,'同步完成',100,'green')+text(24,221,'教材已更新，',28,weight=700)+text(24,260,'進度完整保留。',28,weight=700)+rect(24,296,364,178)+lines(44,335,['App 教材             已儲存 108 筆','本機 CSV             寫入完成','Google Sheets        寫入完成','最後成功同步         今天 09:41'],14,step=35)+button(515,'開始學習這個來源',ico='play')+button(579,'回到來源','outline')+row(659,'另存同步前的復原副本','檔案寫入異常時可使用','download')
save(14,'sync-result','同步結果','日文旅行 · 本機檔案與雲端',b)
# AI branch.
b=field(136,'AI 供應商','ChatGPT / OpenAI API             ▾')+field(214,'API 金鑰','',secret=True)+text(24,295,'加密儲存在此裝置，不放入備份。',12,P['muted'])+field(324,'模型','gpt-4.1-mini                    ▾')+button(410,'載入模型清單 / 檢查金鑰','outline')+row(478,'進階 API 設定','API 格式、輸出格式與相容網址','settings')+note(574,'API 費用由供應商計收',['ChatGPT 訂閱不等於 API 可用額度。','模型清單以這組金鑰的回應為準。'],h=100)+button(706,'儲存 AI 設定')
save(15,'ai-settings','AI API 與模型','設定一次，之後可直接產生教材',b,3)
b=field(136,'搜尋或輸入模型 ID','gpt-4.1')+pill(24,220,'帳號回傳的模型',162,'green')+rect(24,268,364,226)+radio(280,'gpt-4.1-mini','目前選用 · 請依供應商計費確認')+radio(350,'gpt-4.1','可用模型；不代表目前有足夠額度',False)+radio(420,'手動輸入模型 ID','清單沒有需要的模型時使用',False)+button(530,'選用此模型')+note(610,'模型與輸出格式需相容',['若模型不支援 JSON Schema，','可在進階設定改用 JSON 或提示詞模式。'],h=106)
save(16,'ai-models','選擇模型','ChatGPT / OpenAI API',b,3)
b=pill(24,136,'建立新來源',128)+pill(162,136,'加入既有來源',146,'plain')+field(186,'新來源名稱','日文旅行')+field(260,'主題','搭車、問路與車站會話')+field(334,'分類 / 教材類型','旅遊  /  句子                    ▾')+field(408,'語言','繁體中文 / 日本語                ▾')+field(482,'難度 / 數量','A1  /  20 筆')+row(555,'目前 AI','OpenAI · gpt-4.1-mini','spark')+note(631,'先產生草稿，再審核儲存',['AI 生成未經查證；尚未建立本機來源。'],h=76)+button(726,'產生待審核教材',ico='spark')
save(17,'ai-generate','用 AI 新增來源','也可加入已存在的本機或雲端來源',b)
b=rect(24,142,364,224)+rect(162,166,88,88,P['pale'],44)+icon(185,189,'spark',42,P['blue'])+text(206,292,'正在產生教材',23,weight=700,anchor='middle')+text(206,328,'已完成請求量  20 / 100',14,P['muted'],anchor='middle')+progress(391,.2)+text(24,435,'已完成批次會逐批保存。',15)+text(24,463,'可以停止，稍後從草稿繼續。',14,P['muted'])+button(510,'停止產生並保留草稿','outline')+note(597,'儲存前仍需要審核',['目前內容只在本機草稿，','尚未寫入檔案或 Google Sheets。'],h=104)
save(18,'ai-progress','產生教材中','日文旅行 · OpenAI',b)
b=pill(24,136,'已選 2 筆',100)+text(388,155,'第 1 / 2 頁',13,P['muted'],anchor='end')+note(184,'AI 生成，未經搜尋或查證',['請確認翻譯、用法與發音後再儲存。'],kind='amber',h=76)+rect(24,278,364,196)+icon(40,299,'check',20,P['blue'])+text(76,316,'01  車站在哪裡？',18,weight=600)+text(76,356,'駅はどこですか？',22,P['blue'],600)+text(76,390,'eki wa doko desu ka',13,P['muted'])+pill(76,416,'編輯這一筆',128)+rect(24,490,364,118)+icon(40,510,'check',20,P['blue'])+text(76,527,'02  請問車票多少錢？',16,weight=600)+text(76,566,'切符はいくらですか？',20,P['blue'])+text(24,642,'只儲存你已選取的教材。',13,P['muted'])+button(670,'建立來源並儲存 2 筆教材')+button(728,'下一頁，繼續審核','outline')
save(19,'ai-review','審核教材','日文旅行 · 可修改翻譯、發音註記與選取',b)
b=rect(164,148,84,84,P['mint'],42)+icon(188,172,'check',36,P['green'])+text(206,286,'本機教材已準備好',25,weight=700,anchor='middle')+text(206,323,'日文旅行 · 已儲存 20 筆',15,P['muted'],anchor='middle')+button(368,'開始學習這個來源',ico='play')+button(430,'檢查 / 安裝離線語音包','secondary',ico='volume')+rect(24,514,364,144)+row(520,'另存並連接 CSV','保留同一來源與學習進度','file')+row(586,'繼續新增教材','相同來源可以分批擴充','plus')+text(24,719,'已儲存內容不需要 Google 或 AI 連線即可學習。',12,P['muted'])
save(20,'ai-result','來源建立完成','審核完成 · 內容已儲存在這台裝置',b)
# Library, session and review.
b=field(136,'搜尋教材','車站')+pill(24,217,'全部來源',104)+pill(138,217,'句子',74)+pill(222,217,'A1',64)+icon(352,219,'filter',24,P['blue'])+text(24,280,'找到 3 筆教材',13,P['muted'])
for y,zh,ja,src in [(300,'車站在哪裡？','駅はどこですか？','日文旅行'),(448,'到車站要多久？','駅までどのくらいですか？','日文旅行'),(596,'在車站見。','駅で会いましょう。','旅遊會話')]:b+=rect(24,y,364,132)+text(40,y+33,zh,18,weight=600)+text(40,y+65,ja,18,P['blue'])+text(40,y+101,src+' · 句子 · A1',12,P['muted'])+icon(349,y+96,'chevron',18,P['muted'])
save(21,'lesson-search','所有教材','搜尋與分類 · 不改變目前播放場次',b)
b=pill(24,136,'日文旅行',106)+pill(140,136,'已收藏',90,'green')+rect(24,182,364,207)+text(44,221,'車站在哪裡？',25,weight=600)+text(44,269,'駅はどこですか？',27,P['blue'],700)+text(44,311,'eki wa doko desu ka',14,P['muted'])+text(44,360,'句子 · 旅遊 · A1',13,P['muted'])+button(413,'編輯本機教材','outline',ico='edit')+rect(24,488,364,144)+row(494,'學習狀態','未學會 · 播放 3 次','chart')+row(560,'下次複習','明天 · 普通評分','sync')+button(665,'加入複習','secondary')+button(727,'暫停 7 天 / 30 天','outline')
save(22,'lesson-detail','教材詳情','修改文字會保留學習進度',b)
b=field(136,'教材來源','日文旅行                         ▾')+field(212,'母語 / 目標語','繁體中文  →  日本語              ▾')+pill(24,299,'20 筆',80)+pill(114,299,'50 筆',80,'plain')+pill(204,299,'100 筆',88,'plain')+pill(302,299,'全部',86,'plain')+field(345,'自訂筆數','20')+rect(24,430,364,128)+toggle(444,'亂數播放','同一場次不重複',False)+toggle(504,'排除已學會','暫停中的教材仍會排除',True)+row(579,'進階篩選','分類、類型、程度、狀態與近期排除','filter')+row(646,'朗讀設定','順序、五語語速、重複與間隔','volume')+button(726,'建立場次並播放',ico='play')
save(23,'session','建立學習場次','設定將套用到新場次',b,1)
b=field(136,'分類','全部分類                         ▾')+field(212,'教材類型','全部 / 單字 / 短語 / 句子         ▾')+field(288,'程度','全部程度                         ▾')+field(364,'學習狀態','全部 / 收藏 / 複習 / 到期         ▾')+field(440,'避開最近播放','不排除                           ▾')+toggle(527,'優先複習','先排手動複習優先級',True)+note(601,'目前場次不會被重排',['回到建立場次後，確認新場次才生效。'],h=80)+button(718,'套用篩選並返回')
save(24,'session-filters','進階篩選','保留你選定的來源與語言',b,1)
b=field(136,'朗讀方向','母語 → 目標語                    ▾')+toggle(218,'顯示其他語言','只影響文字顯示，不增加朗讀語言',True)+section(305,'各語言語速')
for y,label,val,f in [(324,'繁體中文','1.00×',.55),(382,'English','0.85×',.4),(440,'Bahasa Indonesia','0.85×',.4),(498,'日本語','0.85×',.4),(556,'한국어','0.85×',.4)]:b+=text(36,y+18,label,14)+text(376,y+18,val,13,P['blue'],anchor='end')+rect(36,y+33,340,4,P['line'],2)+rect(36,y+33,340*f,4,P['blue'],2)+f'<circle cx="{36+340*f}" cy="{y+35}" r="7" fill="{P["blue"]}"/>'
b+=row(616,'重複 / 間隔 / 循環','1 次 · 2 秒 · 關閉','sync')+button(714,'儲存朗讀設定')
save(25,'speech-settings','朗讀設定','套用到下次建立的場次',b,1)
b=pill(24,136,'20 筆 · 順序',130)+text(24,200,'目前播放',13,P['muted'])+card(218,'08  車站在哪裡？','駅はどこですか？',ico='volume',h=92)+section(355,'接下來')
for y,num,a,c in [(378,'09','請問車票多少錢？','切符はいくらですか？'),(480,'10','我要去東京。','東京に行きたいです。'),(582,'11','下一班車幾點？','次の電車は何時ですか？')]:b+=card(y,num+'  '+a,c,ico='play',h=88)
b+=note(694,'場次保持建立時的順序',['新增或同步教材後，請建立新場次載入。'],h=78)
save(26,'queue','目前播放清單','日文旅行 · 繁中 → 日文',b,1)
b=rect(24,136,364,106)+metric(44,178,'12','目前到期')+metric(200,178,'48','已安排複習')+button(266,'開始到期複習',ico='sync')+section(362,'最近七天','依實際評分紀錄統計')+rect(24,407,364,180)
for i,(h,l) in enumerate(zip([42,70,38,86,56,104,75],['一','二','三','四','五','六','日'])):x=48+i*47;b+=rect(x,550-h,24,h,P['blue'] if i==6 else '#BBD7F3',6)+text(x+12,573,l,12,P['muted'],anchor='middle')
b+=text(40,442,'評分 42 次 · 重學 6 次',14,weight=600)+note(611,'評分不等於自動學會',['重學 / 困難 / 普通 / 容易安排下次複習。','收藏、已學會與暫停設定保持不變。'],h=102)
save(27,'review','複習與學習紀錄','依目前語言建立到期場次',b,0)
b=pill(24,136,'目前引擎 · 系統文字轉語音',252)+rect(24,184,364,356)
for y,title,available in [(194,'繁體中文',True),(260,'English',True),(326,'Bahasa Indonesia',True),(392,'日本語',True),(458,'한국어',False)]:b+=row(y,title,'離線語音可用' if available else '尚未安裝離線語音',ico='check' if available else 'download',trail='可用' if available else '安裝')
b+=button(570,'重新檢查語音包','secondary')+button(632,'開啟系統語音包安裝',ico='download')+note(705,'安裝完成後請返回重新檢查',['只使用引擎標示已安裝的離線 voice。'],h=76)
save(28,'offline-voices','離線語音包','教材文字與語音包需要分別準備',b,3)
# Backup and settings.
b=rect(24,136,364,193)+radio(147,'完整備份','教材文字、來源、進度、場次與設定')+radio(231,'僅學習紀錄','不含教材文字；不能獨立離線換機',False)+note(355,'標準 ZIP 格式，不加密',['請存放在你信任的位置。','不包含 API 金鑰、Google 授權或語音包。'],h=102)+button(488,'匯出 ZIP',ico='download')+button(552,'選擇 ZIP 並預覽還原','outline',ico='folder')+section(653,'換機後仍需完成')+lines(24,684,['重新安裝所需離線語音包。','重新選取本機檔案並連接 Google / AI。'],13,P['muted'],25)
save(29,'backup','備份與換機','在設定中保存教材與學習紀錄',b,3)
b=pill(24,136,'備份檢查通過',144,'green')+rect(24,184,364,158)+lines(40,220,['建立日期      2026 / 11 / 22','內容          完整教材與學習紀錄','來源 / 教材   3 個 / 240 筆','學習進度      68 筆已學會'],14,step=30)+section(384,'選擇還原方式')+rect(24,407,364,168)+radio(420,'智慧合併','依穩定 ID 合併，保留較新的本機進度')+radio(500,'完整覆蓋','取代目前資料；下一步將再次確認',False)+note(598,'這個畫面還沒有修改你的資料',['確認後才還原；檔案存取權不會移轉。'],h=80)+button(716,'確認並還原')
save(30,'restore-preview','預覽備份內容','先檢查版本、完整性與內容，再還原',b,3)
b=rect(164,146,84,84,P['mint'],42)+icon(188,170,'check',36,P['green'])+text(206,283,'還原完成',28,weight=700,anchor='middle')+text(206,321,'教材、學習進度與複習排程已保留。',14,P['muted'],anchor='middle')+card(370,'檢查離線語音包','新手機需重新下載使用的語音',ico='volume',h=90)+card(474,'重新連接本機檔案','沿用既有來源與進度',ico='file',h=90)+button(607,'回首頁開始學習',ico='home')
save(31,'restore-result','還原結果','完整備份 · 智慧合併',b,3)
b=section(158,'介面語言')+rect(24,180,364,220)+radio(194,'繁體中文','預設介面語言')+radio(264,'English','English interface',False)+radio(334,'Bahasa Indonesia','Antarmuka Bahasa Indonesia',False)+section(447,'外觀')+rect(24,472,364,92)+toggle(488,'深色模式','適合夜間閱讀',False)+note(596,'介面語言與教材語言分開',['切換介面不會變更你正在學習的語言。'],h=80)+button(716,'儲存偏好')
save(32,'appearance','語言與外觀','繁中 / 英文 / 印尼文介面',b,3)
b=rect(24,136,364,156)+toggle(151,'僅 Wi-Fi 同步','避免行動網路下載',True)+toggle(226,'啟動時同步','只處理可自動同步的雲端來源',True)+note(322,'本機讀寫不需要網路',['檔案與 Sheets 的雙向合併需手動預覽。','網路不符政策時保留既有離線教材。'],h=104)+row(460,'Google 授權','可停用同步；本機教材保留','cloud')+row(537,'API 金鑰管理','只存在此裝置，不納入 ZIP','shield')+button(664,'儲存同步偏好')
save(33,'sync-preferences','同步與連線偏好','網路中斷不影響本機教材',b,3)
b=field(136,'API 格式','自動 / Responses / Chat          ▾')+field(212,'輸出格式','JSON Schema                     ▾')+field(288,'相容 API base URL','https://api.example.com/v1')+note(381,'網址只在相容供應商時可編輯',['請填 HTTPS base URL，不包含 API 金鑰。','不同供應商 / 網址不共用已存金鑰。'],h=102)+button(526,'儲存進階設定')+button(590,'移除此供應商的金鑰','outline')+text(24,688,'設定變更後，請重新載入模型或驗證生成。',12,P['muted'])
save(34,'ai-advanced','進階 API 設定','AI API 與模型  ›  進階',b,3)
# Android surfaces, clearly indicated as system illustrations.
b=text(206,198,'09:41',64,weight=600,anchor='middle')+text(206,236,'11 月 22 日，星期日',16,P['muted'],anchor='middle')+rect(24,292,364,252)+icon(42,312,'headphones',20,P['blue'])+text(74,328,'Language Listener',13,weight=600)+text(44,380,'車站在哪裡？',24,weight=700)+text(44,417,'駅はどこですか？',22,P['blue'])+progress(449,.4)+icon(92,484,'prev',26)+icon(194,480,'pause',32)+icon(296,484,'next',26)+note(600,'背景播放持續使用離線音訊',['支援系統通知與標準耳機媒體鍵。','特殊耳機手勢與鎖屏樣式依裝置而異。'],h=104)
save(35,'lockscreen','鎖屏與媒體通知','Android 系統畫面示意 · 樣式依裝置而異',b,1,False,nav=False)
b=rect(24,136,364,50)+icon(38,150,'folder',22,P['blue'])+text(76,168,'這台裝置  ›  Documents',15,weight=600)+row(204,'Japanese-travel.xlsx','100 KB · 今天','file')+row(278,'LanguageListener-lessons.csv','36 KB · 今天','file')+row(352,'LanguageListener-full.zip','1.2 MB · 昨天','file')+note(480,'由 Android 管理單一檔案存取權',['選擇可離線開啟、可寫入的位置。','App 不掃描或取得整個資料夾。'],h=104)+button(644,'使用這個檔案')+text(24,736,'此為系統選擇器示意；實際外觀依裝置而異。',12,P['muted'])
save(36,'system-files','選擇檔案 / 儲存位置','Android 系統檔案選擇器示意',b,2,False,nav=False)
# Explicit empty, busy and failure states.
states=[
('empty-library','還沒有教材','可以先從本機開始。',['建立空白 CSV、選擇 Excel，','或直接新增一筆教材，不需要登入 Google。'],'建立本機教材','file','blue'),
('missing-voice','尚缺日文離線語音','目前教材已儲存，不會遺失。',['先安裝日文離線語音包，','完成後返回檢查，再開始播放。'],'開啟語音包安裝','volume','amber'),
('ai-error','教材尚未產生','供應商拒絕此請求 · HTTP 400',['檢查模型與輸出格式是否相容。','已完成的草稿會保留；可修改設定後再試。'],'檢查 AI 設定','spark','red'),
('sync-conflict','有 1 筆教材修改衝突','同步已停止，沒有直接覆蓋。',['App 與檔案都修改了同一筆教材。','先調整成一致，再重新預覽同步。'],'查看衝突內容','sync','amber'),
('file-permission','需要重新選擇檔案','本機教材與學習進度仍在。',['檔案已移動，或系統存取權已失效。','選擇原檔案並接到此既有來源。'],'重新選擇檔案','folder','amber'),
('restore-error','無法匯入這份備份','目前資料沒有被修改。',['檔案可能損毀，或備份版本不受支援。','請選擇另一份 ZIP 或更新 App。'],'重新選擇 ZIP','shield','red'),
('offline-cloud','目前沒有網路','你仍可播放與編輯本機教材。',['Google Sheets 與 AI 需要連線。','恢復網路後，再手動同步或繼續產生。'],'回到本機教材','cloud','blue'),
('no-matches','沒有符合條件的教材','目前來源與語言配對沒有可播放內容。',['確認兩種語言都有文字，','或調整分類、暫停期限與已學會篩選。'],'調整場次篩選','search','blue')]
for i,(slug,title,sub,body,cta,ico,kind) in enumerate(states,37):
 b=rect(164,168,84,84,P['pale'] if kind=='blue' else P['rose'] if kind=='red' else P['sand'],42)+icon(188,192,ico,36,P['blue'] if kind=='blue' else P['red'] if kind=='red' else P['amber'])+text(206,307,title,24,weight=700,anchor='middle')+text(206,348,sub,13,P['muted'],anchor='middle')+lines(40,407,body,14,step=28)+button(505,cta)+button(567,'返回','outline')
 if slug=='ai-error':b+=row(653,'展開錯誤詳情','只顯示清理後的服務訊息','alert')
 if slug=='sync-conflict':b+=note(649,'車站在哪裡？',['App：駅はどこですか？','檔案：駅はどちらですか？'],h=108)
 save(i,slug,title,'狀態畫面 · '+sub,b,2)
# Dialogs rendered as complete phone surfaces for consistent context.
dialogs=[
('rename','重新命名來源',['教材與學習進度會完整保留。','新名稱：日文旅行會話'],'儲存名稱',False),
('remove-source','移除這個來源？',['教材將從可播放清單移除。','既有學習紀錄保留，之後可以接回。'],'移除來源',True),
('replace-draft','重新產生教材？',['成功產生的新批次會取代待審核草稿。','尚未儲存的選取與修改將無法保留。'],'重新產生',False),
('publish','建立來源並儲存？',['將建立「日文旅行」，儲存已選 20 筆。','不需要 Google 授權；尚未寫入檔案。'],'建立並儲存',False),
('restore-replace','完整覆蓋目前資料？',['將以備份取代本機教材、進度與場次。','未審核草稿及舊檔案綁定會清除。'],'確認完整覆蓋',True),
('clear-review','清除此筆複習排程？',['收藏、已學會與歷史評分會保留。','這一筆將不再依原日期出現在到期複習。'],'清除排程',True),
('auth-cancelled','Google 授權已取消',['沒有初始化或寫入你的試算表。','可以稍後再連接，或先使用本機教材。'],'先用本機教材',False),
('quota','API 額度不足',['供應商回覆目前無可用額度。','請檢查 API 帳戶計費；已有草稿會保留。'],'回到 AI 設定',False)]
for i,(slug,title,ls,cta,danger) in enumerate(dialogs,45):
 b=card(142,'日文旅行','100 筆 · 已存本機',h=100)+button(266,'開始學習這個來源')+rect(0,126,412,664,'#172A4388',0)+rect(24,292,364,286,P['card'],28)+icon(44,313,'alert' if danger else 'file',26,P['red'] if danger else P['blue'])+text(44,374,title,21,weight=700)+lines(44,412,ls,12,step=24)+button(490,cta,'danger' if danger else 'primary',x=44,w=324)+text(206,563,'取消 / 關閉',14,P['blue'],anchor='middle')
 save(i,'dialog-'+slug,title,'對話框 · 保留原畫面脈絡',b,3 if 'restore' in slug or 'review' in slug else 2)
# Dark mode examples reuse identical hierarchy.
save(53,'home-dark','Language Listener','深色模式 · 四個主頁採相同語意與對比規則',home,0,False,True)
save(54,'player-dark','播放器','深色模式 · 繁中 → 日文',player,1,False,True)
# Build overview boards by embedding vector groups, not raster screenshots.
def inner(svg):return svg[svg.index('<defs>'):svg.rindex('</svg>')]
def board(filename,ids,cols,title,subtitle):
 cellw,cellh=460,970;w=cols*cellw+64;rows=(len(ids)+cols-1)//cols;h=180+rows*cellh+50
 s=rect(0,0,w,h,'#EAF0F7',0)+text(40,61,title,34,weight=700)+text(40,100,subtitle,16,P['muted'])
 for j,n in enumerate(ids):
  screen=next(a for a in SCREENS if a['id']==n);x=40+j%cols*cellw;y=145+j//cols*cellh
  s+=text(x,y+10,f'{n:02d}  {screen["title"]}',16,weight=600)+f'<g transform="translate({x} {y+32})">'+inner((OUT/(screen['slug']+'.svg')).read_text())+'</g>'
 svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img"><title>{e(title)}</title><g font-family="Noto Sans CJK TC, sans-serif">{s}</g></svg>'
 (OUT/filename).write_text(svg)
board('00-main-overview.svg',[1,2,3,4],4,'Language Listener / 介面設計提案','四個主頁 · 本機優先、先準備教材再直接學習 · 所有數據為示意，不代表使用者實際資料')
board('00-all-screens.svg',[a['id'] for a in SCREENS],6,'Language Listener / 全介面總覽','54 個畫面 · 四主頁、建立教材、AI、同步、學習、備份、錯誤、對話框及深色模式')
board('00-offline-flow.svg',[5,7,8,9,6,28,23,2],4,'本機離線學習 / 完整流程','選取或建立檔案 → 初始化 → 語音就緒 → 直接學習；雲端為選用')
board('00-ai-flow.svg',[15,16,17,18,19,20,6,2],4,'AI 建立教材 / 完整流程','設定 API 與模型 → 產生草稿 → 人工審核 → 建立本機來源 → 離線學習')
# Adaptive tablet: navigation rail and two-pane source workspace.
w,h=1280,900
s=rect(0,0,w,h,P['bg'],0)+rect(0,0,100,h,P['card'],0)+text(30,48,'LL',26,P['blue'],700)
for y,ic,label in [(135,'home','首頁'),(235,'headphones','播放'),(335,'library','教材'),(435,'settings','設定')]:
 if ic=='library':s+=rect(15,y-12,70,76,P['pale'],18)
 s+=icon(38,y,ic,26,P['blue'])+text(50,y+47,label,12,P['blue'],anchor='middle')
s+=text(132,56,'教材庫',30,weight=700)+text(132,86,'平板 / 橫向雙欄版面 · 保持四大導覽目的地',14,P['muted'])+rect(124,114,358,758,P['card'],22)+rect(500,114,756,758,P['card'],22)
s+=text(148,153,'來源',22,weight=700)+rect(144,177,318,48,P['pale'],24)+text(303,207,'＋ 新增來源',16,P['blue'],600,'middle')
for y,title,detail,active in [(256,'日文旅行','100 筆 · 本機',True),(352,'韓文日常','80 筆 · CSV',False),(448,'商務三語','60 筆 · Sheets',False)]:s+=rect(144,y,318,82,P['pale'] if active else P['bg'],16)+text(166,y+32,title,18,weight=600)+text(166,y+60,detail,13,P['muted'])
s+=text(528,161,'日文旅行',28,weight=700)+pill(528,181,'可離線學習',126,'green')+button(146,'開始學習','primary',x=1016,w=212,ico='play')+text(528,257,'教材',20,weight=700)+text(1208,257,'新增教材  ＋',15,P['blue'],anchor='end')
for y,a,b in [(294,'車站在哪裡？','駅はどこですか？'),(409,'請問車票多少錢？','切符はいくらですか？'),(524,'我要去東京。','東京に行きたいです。')]:s+=rect(528,y,700,96,P['bg'],16)+text(548,y+33,a,20,weight=600)+text(548,y+68,b,20,P['blue'])+icon(1182,y+33,'edit',22,P['blue'])
s+=text(528,690,'來源與同步',20,weight=700)+button(715,'同步本機檔案','secondary',x=528,w=332,ico='sync')+button(715,'預覽 Google Sheets','outline',x=878,w=350,ico='cloud')+text(528,816,'窄螢幕改為來源列表 → 來源詳情；選取狀態與播放不因旋轉丟失。',14,P['muted'])
(OUT/'55-tablet.svg').write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="900" viewBox="0 0 1280 900"><title>平板雙欄版面</title><g font-family="Noto Sans CJK TC, sans-serif">{s}</g></svg>')
# Interactive gallery keeps every original SVG independently editable.
items=[]
for a in SCREENS:
 items.append(f'<article id="s{a["id"]}"><h2>{a["id"]:02d} {e(a["title"])}</h2><a href="{a["slug"]}.svg" target="_blank"><img loading="lazy" src="{a["slug"]}.svg" alt="{e(a["title"])}" /></a><p>{e(a["subtitle"])}</p><a href="{a["slug"]}.svg" download>下載 SVG</a></article>')
html='''<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Language Listener — 全介面 SVG</title><style>*{box-sizing:border-box}body{font-family:"Noto Sans CJK TC",sans-serif;background:#EAF0F7;color:#172A43;margin:0}header{padding:32px;max-width:1400px;margin:auto}h1{margin:0 0 12px;font-size:32px}p{line-height:1.8}nav{display:flex;gap:12px;flex-wrap:wrap}a{color:#2866A8}nav a{background:white;padding:12px 18px;border-radius:22px;text-decoration:none}main{padding:20px 32px;display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:24px;max-width:1800px;margin:auto}article{padding:16px;background:#fff;border-radius:24px}article img{display:block;width:100%;max-width:412px;margin:auto;border:1px solid #E0E7F0;border-radius:26px}article h2{font-size:18px}article p{font-size:13px;color:#66788E}#search{margin:20px 0 0;padding:14px;width:min(100%,480px);border:1px solid #CCD8E8;border-radius:12px;font:inherit}aside{padding:20px 32px;max-width:1400px;margin:auto}.wide{width:100%;border-radius:16px}</style><header><h1>Language Listener</h1><p>全介面 SVG 設計提案 · 54 個手機畫面＋平板版。先確認介面，再安排 App 改版。畫面中的教材與數字皆為示例，無真實個人資料。</p><nav><a href="00-main-overview.svg">四主頁總覽</a><a href="00-all-screens.svg">全部 SVG 總覽</a><a href="00-offline-flow.svg">本機學習流程</a><a href="00-ai-flow.svg">AI 建材流程</a><a href="55-tablet.svg">平板版</a><a href="DESIGN_NOTES.md">設計與畫面索引</a></nav><input id="search" placeholder="搜尋畫面，例如：備份、AI、語音、同步" aria-label="搜尋畫面"></header><main>'''+''.join(items)+'''</main><aside><h2>55 平板 / 橫向版面</h2><a href="55-tablet.svg"><img class="wide" src="55-tablet.svg" alt="平板雙欄版面"></a></aside><script>document.getElementById('search').addEventListener('input',e=>{for(const a of document.querySelectorAll('article'))a.hidden=!a.textContent.toLowerCase().includes(e.target.value.toLowerCase())})</script></html>'''
(OUT/'index.html').write_text(html)
notes='''# Language Listener 全介面 SVG 設計提案

2026-11-22。以目前 0.6.0 功能及產品／UI 規格為基礎；本次只繪製介面，不更動 APK。

## 閱讀方式

- `00-main-overview.svg`：首頁、播放器、教材庫、設定。
- `00-all-screens.svg`：54 個手機畫面、狀態與對話框；可放大檢視。
- `00-offline-flow.svg` / `00-ai-flow.svg`：兩條主要操作流程。
- `index.html`：解壓後直接開啟，可搜尋並逐張檢視／下載 SVG，不依賴網路。
- `55-tablet.svg`：平板及橫向雙欄設計。

## 設計原則

保留四區導覽、Material 3 與既有藍／淡藍色。把目前的長表單分為有明確入口的畫面：來源 → 教材；新增 → 選擇方式；API 設定 → 產生 → 審核 → 本機學習。此為待實作的導航與排版提案，並非宣稱現有 APK 已改成這些畫面。

每頁只有一個主要行動。離線準備狀態可見；本機學習不要求 Google 或 API key。來源管理與 Google 同步分開，AI 草稿在確認前不建立來源；進度不因文字修改或同步而重置。API 費用、人工審核、寫入、覆蓋與失敗情境在相關步驟呈現。

所有統計、教材、檔名及日期為設計示例；正式 UI 必須來自真實資料。沒有加入語音辨識、AI 對話、自動雲端同步等尚未實作功能。鎖屏與檔案選擇器屬 Android 系統示意，實際依裝置呈現。這組 SVG 不取代功能及實機驗收。

## 尺寸與元件

- 手機基準 412 × 892；平板 1280 × 900。16–24 邊距、48 主要按鈕、24 圖示；實作互動區最少 48dp。
- 文字：主要標題 24–28、內文 14–16、輔助 12–13。Noto Sans CJK / 系統無襯線字型，SVG 保留可編輯文字，不轉圖片。
- 主色藍 #397FC9、按鈕與藍色文字 #2867A7（提高對比）、輔助文字 #5C6E84、主要文字 #172A43、淡藍 #E8F3FF、背景 #F6F8FC。綠表示已就緒，橘表示需要處理，紅表示失敗或破壞性動作；同時使用文字／圖示，不只靠顏色。
- 導覽固定底部；長內容在內容區捲動。拆頁讓表單及最後確認不被鍵盤或底欄遮住；動態字級需採自適應高度，不可照 SVG 固定裁切。
- 深色版保留相同閱讀順序。平板使用導覽側欄與來源／教材雙欄；旋轉不重建場次。
- TalkBack 按鈕需語意標籤、選取狀態與焦點順序；製作 Android 介面時再次檢查對比、200% 字級、系統 inset、鍵盤與實機。

## 對照流程

- 本機：05 → 07 → 08 → 系統選檔 36 → 09 → 06 → 28 → 23 → 02。
- AI：15 → 16 → 17 → 18 → 19 → 確認 48 → 20 → 02；錯誤 39／離線 43，取消不丟已完成草稿。
- Google：11 → 原生授權 → 12 → 13 → 14；取消 51、衝突 40、離線 43。
- 備份：29 → 系統選檔 36 → 30 →（完整覆蓋需 49）→ 31；不合法檔案 42。
- 學習：23 → 24／25 → 02 → 26／27；缺 voice 38、沒有符合條件 44。

## 視覺檢查

所有 SVG 已通過 XML 解析，使用本機 Chromium 渲染檢視，手機文字邊界檢查無左右溢出。主頁、播放器、AI 審核、深色與平板預覽已人工檢查。這是設計稿檢查，未執行 Android 建置，未觸發雲端 CI。

## 畫面索引

'''
notes+='\n'.join(f'- {a["id"]:02d} `{a["slug"]}.svg` — {a["title"]}：{a["subtitle"]}' for a in SCREENS)+'\n- 55 `55-tablet.svg` — 平板／橫向版面\n'
(OUT/'DESIGN_NOTES.md').write_text(notes)
(OUT/'screens.json').write_text(json.dumps(SCREENS,ensure_ascii=False,indent=2)+'\n')
# Parse every SVG to fail early on broken XML.
for p in OUT.glob('*.svg'):ET.parse(p)
print(f'Generated {len(SCREENS)} phone screens + tablet + 4 vector boards in {OUT}')
if __name__=='__main__':
 deliver=ROOT.parent/'shared/downloads';deliver.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(deliver/'LanguageListener-UI-SVG-2026-11-22.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(OUT.iterdir()):z.write(p,'LanguageListener-UI-SVG/'+p.name)
 for name in ['00-main-overview.svg','00-all-screens.svg']:(deliver/('LanguageListener-'+name)).write_bytes((OUT/name).read_bytes())
