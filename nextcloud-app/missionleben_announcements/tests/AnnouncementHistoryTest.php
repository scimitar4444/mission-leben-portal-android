<?php
declare(strict_types=1);

// Isolated domain/controller/migration checks, no Nextcloud bootstrap or database.
namespace OCP {
    interface IDBConnection {} interface IRequest {} interface IUserManager {} interface IGroupManager {} interface IUser {}
}
namespace OCP\Security { interface ICrypto {} }
namespace OCP\AppFramework\Utility { interface ITimeFactory {} }
namespace OCP\DB { interface ISchemaWrapper {} class Types { const BIGINT='bigint', TEXT='text', STRING='string'; } }
namespace OCP\Migration { interface IOutput {} class SimpleMigrationStep {} }
namespace OCP\AppFramework {
    class Controller { public function __construct(...$args) {} }
    class Http { const STATUS_UNAUTHORIZED=401, STATUS_BAD_REQUEST=400, STATUS_NOT_FOUND=404, STATUS_SERVICE_UNAVAILABLE=503; }
}
namespace OCP\AppFramework\Http { class JSONResponse { public function __construct(public mixed $data, public int $status=200) {} } }
namespace OCA\MissionLebenAnnouncements\Service { class SignedRequestVerifier {} }
namespace {
    require $argv[1] . '/lib/Service/AnnouncementHistory.php';
    require $argv[1] . '/lib/Controller/AnnouncementHistoryController.php';
    require $argv[1] . '/lib/Migration/Version000004Date20261021183000.php';
    class Clock implements \OCP\AppFramework\Utility\ITimeFactory { public int $now=2000000000; public function getTime(): int { return $this->now; } }
    class Crypto implements \OCP\Security\ICrypto {
        public function encrypt(string $s): string { return 'synthetic-cipher:' . base64_encode($s); }
        public function decrypt(string $s): string {
            if (!str_starts_with($s, 'synthetic-cipher:')) throw new \RuntimeException('Synthetic ciphertext rejected');
            return base64_decode(substr($s,17),true) ?: throw new \RuntimeException('Synthetic ciphertext rejected');
        }
    }
    class Database implements \OCP\IDBConnection {}
    class History extends \OCA\MissionLebenAnnouncements\Service\AnnouncementHistory {
        public array $source=[], $stored=[]; public int $writes=0;
        protected function sourceRows(int $now): array { return $this->source; }
        protected function storedRows(int $now): array { return array_values(array_filter($this->stored, fn($v)=>$v['retain_until']>$now)); }
        protected function writeSnapshot(int $id,string $payload,string $hash,int $until): void {
            $this->writes++; $this->stored[$id]=['announcement_id'=>$id,'payload'=>$payload,'content_hash'=>$hash,'retain_until'=>$until];
        }
        protected function prune(int $now): void { $this->stored=array_column($this->storedRows($now),null,'announcement_id'); }
    }
    class Person implements \OCP\IUser {
        public function __construct(public string $email,public array $groups,public bool $active=true) {}
        public function isEnabled(): bool { return $this->active; }
        public function getEMailAddress(): string { return $this->email; }
    }
    class Users implements \OCP\IUserManager {
        public array $all=[];
        public function get($id): ?Person { return $this->all[$id]??null; }
        public function getByEmail($email): array { return array_values(array_filter($this->all,fn($u)=>str_contains($u->email,$email))); }
    }
    class Groups implements \OCP\IGroupManager { public function getUserGroupIds($u): array { return $u->groups; } }
    class Request implements \OCP\IRequest {}
    class Controller extends \OCA\MissionLebenAnnouncements\Controller\AnnouncementHistoryController {
        public ?array $body=['user_id'=>'a','email'=>'']; protected function payload(): ?array { return $this->body; }
    }
    class Schema implements \OCP\DB\ISchemaWrapper {
        public array $tables=[];
        public function hasTable($t): bool { return isset($this->tables[$t]); }
        public function createTable($t): Table { return $this->tables[$t]=new Table(); }
    }
    class Table {
        public array $columns=[], $primary=[], $indices=[];
        public function addColumn($name,$type,$options): void { $this->columns[$name]=[$type,$options]; }
        public function setPrimaryKey($fields): void { $this->primary=$fields; }
        public function addIndex($fields,$name): void { $this->indices[$name]=$fields; }
    }
    class Output implements \OCP\Migration\IOutput {}
    $checks=0;
    function check($condition,string $message): void { global $checks; if(!$condition) throw new \RuntimeException($message); $checks++; }
    function row($id,$group,$time,$delete=0): array { return ['announcement_id'=>$id,'announcement_subject'=>'Synthetic '.$id,
        'announcement_plain_message'=>'Original readable message','announcement_message'=>'','announcement_user'=>'IT',
        'announcement_time'=>$time,'announcement_delete_time'=>$delete,'gid'=>$group]; }
    $clock=new Clock(); $crypto=new Crypto(); $h=new History(new Database(),$crypto,$clock); $published=$clock->now-60;
    $h->source=[row(1,'everyone',$published,$clock->now+10),row(2,'house-a',$published)];
    $h->synchronize(); check($h->writes===2,'capture two published posts');
    check(!str_contains($h->stored[1]['payload'],'Original readable message'),'source snapshots use ICrypto, not plaintext');
    $h->synchronize(); check($h->writes===2,'unchanged source must not rewrite ciphertext each tick');
    check(count($h->visible(['everyone','house-a']))===2,'authorized source visible');
    $clock->now+=11;
    check($h->visible(['everyone'])[0]['archived']===true,'expiry is archived, not removed');
    $h->source=[];
    $visible=$h->visible(['everyone','house-a']);
    check(count($visible)===2 && $visible[0]['archived'] && $visible[1]['archived'],'physical source deletion preserves 14-day history');
    check($visible[0]['message']==='Original readable message','deleted post remains readable, not just a placeholder');
    check(count($h->visible(['everyone']))===1,'current group removal revokes house-specific archive');
    check(!isset($visible[0]['groups']),'group audience must never leave the adapter');
    $h->source=[row(2,'house-b',$published)];
    check(count($h->visible(['everyone','house-a']))===1,'live audience edits override stale snapshots before sync');
    $h->synchronize(); $h->source=[];
    check(count($h->visible(['house-b']))===1,'updated snapshot follows source groups');
    $clock->now=$published+History::RETENTION_SECONDS-1;
    check(count($h->visible(['everyone','house-b']))===2,'last second inside retention included');
    $clock->now++;
    check($h->visible(['everyone','house-b'])===[],'exact fourteen-day boundary excludes archive');
    $h->synchronize(); check($h->stored===[],'expired ciphertext pruned');
    $h->source=[row(3,'everyone',$clock->now-History::RETENTION_SECONDS-10)];
    $h->synchronize(); check($h->stored===[] && count($h->visible(['everyone']))===1,'old active posts stay live without indefinite archiving');
    $h->source=[]; check($h->visible(['everyone'])===[],'old posts outside fourteen days have no deleted-source history');
    $users=new Users(); $users->all=['a'=>new Person('a@example.invalid',['house-a']),'off'=>new Person('off@example.invalid',['house-a'],false)];
    $h->source=[row(4,'house-a',$clock->now-1)]; $h->synchronize(); $h->source=[];
    $c=new Controller('synthetic',new Request(),new \OCA\MissionLebenAnnouncements\Service\SignedRequestVerifier(),$h,$users,new Groups());
    check(count($c->list()->data['announcements'])===1,'controller checks current active UID');
    $users->all['a']->groups=[];
    check($c->list()->data['announcements']===[],'groups rechecked on every history request');
    $c->body=['user_id'=>'gone','email'=>'a@example.invalid']; check($c->list()->status===404,'removed UID cannot fall back to valid email');
    $c->body=['user_id'=>'off']; check($c->list()->status===404,'inactive account denied');
    $c->body=['email'=>'example.invalid']; check($c->list()->status===404,'fuzzy/invalid email denied');
    $c->body=['user_id'=>'a','groups'=>['house-a']]; check($c->list()->status===400,'caller groups rejected');
    $c->body=null; check($c->list()->status===401,'unsigned history denied');
    $c->body=['user_id'=>'a']; $h->stored[4]['payload']='broken';
    check($c->list()->status===503,'corrupt archive fails closed');
    $h->stored=[]; $h->source=array_map(fn($id)=>row($id,'everyone',$clock->now-1),range(1,501));
    check($c->list()->status===503,'overflow fails closed instead of truncating');
    $migration=new \OCA\MissionLebenAnnouncements\Migration\Version000004Date20261021183000(); $schema=new Schema();
    check($migration->changeSchema(new Output(),fn()=>$schema,[])===$schema,'own migration creates schema');
    check(array_keys($schema->tables)===['ml_announcement_history'],'no source/Core tables changed');
    check($schema->tables['ml_announcement_history']->primary===['announcement_id'],'archive ID primary key');
    check($migration->changeSchema(new Output(),fn()=>$schema,[])===null,'migration idempotent');
    echo "Announcement history: $checks synthetic checks passed\n";
}
